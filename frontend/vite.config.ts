import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [vue(), Components({ dts: false, resolvers: [ElementPlusResolver()] })],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080', '/actuator': 'http://localhost:8080' } }
})
