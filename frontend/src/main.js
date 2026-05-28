import { createApp } from 'vue'
import App from './App.vue'

const app = createApp(App)

app.config.errorHandler = (err, instance, info) => {
  const el = document.getElementById('error-overlay')
  if (el) {
    el.style.display = 'block'
    el.innerHTML += '<div><b>Vue Error:</b> ' + err.message + ' | ' + info + '</div>'
  }
  console.error(err, info)
}

app.config.warnHandler = (msg, instance, trace) => {
  console.warn(msg, trace)
}

app.mount('#app')
