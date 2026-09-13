"""Check the isolated running production stack without logging its configuration or credentials."""

import argparse
import json
import os
import shlex
import subprocess
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def verify(env_file: Path) -> None:
    values = {}
    for line in env_file.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key] = shlex.split(value)[0]
    environment = os.environ | values
    compose = ["docker", "compose", "--env-file", str(env_file), "-f", "deployment/docker-compose.yml",
               "-f", "deployment/docker-compose.production.yml", "-f", "deployment/docker-compose.test.yml"]

    def command(arguments: list[str]) -> str:
        result = subprocess.run(compose + arguments, env=environment, capture_output=True, text=True, check=False, timeout=30)
        if result.returncode:
            raise AssertionError("Verification Docker command failed; no configuration was printed")
        return result.stdout.strip()

    services = json.loads(command(["config", "--format", "json"]))["services"]
    assert not services["mysql"].get("ports"), "MySQL must not publish a host port"
    assert not services["redis"].get("ports"), "Redis must not publish a host port"
    assert values["GATEWAY_API_KEY"] != values["GATEWAY_ADMIN_API_KEY"], "Use independent access keys"
    assert services["llm-gateway"]["environment"]["SPRING_DATA_REDIS_PASSWORD"] == values["REDIS_PASSWORD"]
    assert "NOAUTH" in command(["exec", "-T", "redis", "redis-cli", "ping"]), "Redis must reject unauthenticated commands"
    assert command(["exec", "-T", "redis", "sh", "-ec", 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping']) == "PONG"

    def status(path: str, key: str | None = None, post: bool = False) -> int:
        headers = {"Content-Type": "application/json"}
        if key is not None:
            headers["Authorization"] = "Bearer " + key
        request = Request(values["GATEWAY_URL"] + path, headers=headers, data=b"{}" if post else None)
        try:
            with urlopen(request, timeout=10) as response:
                return response.status
        except HTTPError as error:
            code = error.code
            error.close()
            return code

    assert status("/actuator/health") == 200
    for path in ["/api/admin/providers", "/api/%61dmin/providers", "/api;ignored=x/admin/providers"]:
        assert status(path) == 401, "Unauthenticated admin path was accepted"
        assert status(path, values["GATEWAY_API_KEY"]) == 401, "Inference key was accepted for administration"
        assert status(path, values["GATEWAY_ADMIN_API_KEY"]) == 200, "Admin key was rejected"
    assert status("/actuator/prometheus", values["GATEWAY_API_KEY"]) == 401
    assert status("/actuator/prometheus", values["GATEWAY_ADMIN_API_KEY"]) == 200
    assert status("/v1/messages", values["GATEWAY_ADMIN_API_KEY"], post=True) == 401
    assert status("/%761/chat/completions", post=True) == 401
    print("Production checks passed: private storage ports, Redis authentication, separate keys and protected encoded paths")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("env_file", type=Path)
    verify(parser.parse_args().env_file)
