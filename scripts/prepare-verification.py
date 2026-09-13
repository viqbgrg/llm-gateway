"""Create private, disposable configuration for production-profile verification."""

import argparse
import base64
import json
import secrets
import shlex
import socket
from pathlib import Path


def prepare(directory: Path, port: int) -> None:
    directory = directory.resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ValueError("Use an empty directory for disposable verification files")
    directory.chmod(0o700)
    if port == 0:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
    keyring = directory / "provider-keyring.json"
    keyring.write_text(json.dumps({"verification": base64.b64encode(secrets.token_bytes(32)).decode()}))
    keyring.chmod(0o600)
    values = {
        "COMPOSE_PROJECT_NAME": "llm-gateway-check-" + secrets.token_hex(4),
        "GATEWAY_PORT": str(port), "GATEWAY_URL": "http://127.0.0.1:" + str(port),
        "MYSQL_PASSWORD": secrets.token_hex(24), "MYSQL_ROOT_PASSWORD": secrets.token_hex(24),
        "REDIS_PASSWORD": secrets.token_hex(24),
        "GATEWAY_API_KEY": secrets.token_hex(32), "GATEWAY_ADMIN_API_KEY": secrets.token_hex(32),
        "GATEWAY_ACTIVE_KEY_ID": "verification", "GATEWAY_KEYRING_FILE": str(keyring),
        "GATEWAY_ALLOW_LEGACY_READS": "false",
    }
    env = directory / "stack.env"
    env.write_text("".join(f"{key}={shlex.quote(value)}\n" for key, value in values.items()))
    env.chmod(0o600)
    print("Disposable verification configuration prepared; credentials were not printed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--port", type=int, default=0)
    options = parser.parse_args()
    if not 0 <= options.port <= 65535:
        parser.error("port must be between 0 and 65535")
    prepare(options.directory, options.port)
