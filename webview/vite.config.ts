import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: {
    // Gradle 플러그인과 연동하기 위해 빌드 결과물을 지정 (플러그인의 resources 폴더)
    outDir: '../build/webview',
    emptyOutDir: true
  }
})
