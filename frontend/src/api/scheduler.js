const BASE = '/api'

async function request(url, options = {}) {
  const resp = await fetch(`${BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  return resp.json()
}

export default {
  getState: () => request('/simulation/state'),
  start: (cjId) => request('/simulation/start', { method: 'POST', body: JSON.stringify({ cjId: cjId || '' }) }),
  pause: () => request('/simulation/pause', { method: 'POST' }),
  reset: () => request('/simulation/reset', { method: 'POST' }),
  step: () => request('/simulation/step', { method: 'POST' }),
  setSpeed: (speed) => request('/simulation/speed', { method: 'POST', body: JSON.stringify({ speed }) }),
  getGantt: () => request('/simulation/gantt'),
  getEvents: () => request('/simulation/events'),
  getDeviceConfig: () => request('/config/device'),
  getScheduleConfig: () => request('/config/schedule'),
  getJobConfig: () => request('/config/job'),
  reloadConfig: () => request('/config/reload', { method: 'POST' }),
  getFoups: () => request('/simulation/foups'),
  getRobots: () => request('/simulation/robots'),
  generateReport: () => request('/report/generate', { method: 'POST' })
}
