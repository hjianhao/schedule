<template>
  <div class="app">
    <header class="app-header">
      <h1>EPI 半导体设备调度调试器</h1>
      <div class="header-stats" v-if="state">
        <span class="stat">时间: {{ formatTime(state.currentTimeSec) }}</span>
        <span class="stat">状态: <span :class="'status-' + (state.status || '').toLowerCase()">{{ statusText }}</span></span>
        <span class="stat">完成: {{ state.completedWafers }}/{{ state.totalWafers }}</span>
        <span class="stat">WPH: {{ state.currentWPH ? state.currentWPH.toFixed(1) : '-' }}</span>
      </div>
    </header>
    <ControlPanel
      :status="state?.status"
      :speed="speed"
      :controlJobs="jobConfig?.controlJobs"
      @start="onStart"
      @start-job="onStartJob"
      @pause="onPause"
      @step="onStep"
      @reset="onReset"
      @speed-change="onSpeedChange"
      @show-gantt="showGantt = !showGantt"
    />
    <div class="main-content">
      <div class="left-panel">
        <ToolLayout :state="state" :foups="foupsData" :robots="robotsData" :passthroughs="deviceConfig?.passthroughs" :robotTimes="robotTimes" />
      </div>
      <div class="right-panel">
        <div class="event-log">
          <h3>事件日志</h3>
          <div class="log-content" ref="logRef">
            <div v-for="(evt, i) in events" :key="i" class="log-entry">{{ evt }}</div>
          </div>
        </div>
      </div>
    </div>
    <div v-if="showGantt" class="gantt-section">
      <GanttChart :data="ganttData" :currentTime="state?.currentTimeSec || 0" :robotTimes="robotTimes" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch, computed } from 'vue'
import api from './api/scheduler.js'
import ControlPanel from './components/ControlPanel.vue'
import ToolLayout from './components/ToolLayout.vue'
import GanttChart from './components/GanttChart.vue'

const state = ref(null)
const events = ref([])
const ganttData = ref([])
const foupsData = ref({})
const robotsData = ref([])
const speed = ref(10)
const showGantt = ref(true)
const logRef = ref(null)
const deviceConfig = ref(null)
const jobConfig = ref(null)
const reportGenerated = ref(false)
let pollTimer = null
let ganttTimer = null

const statusText = ref('')
watch(() => state.value?.status, (s) => {
  const map = { IDLE: '就绪', RUNNING: '运行中', PAUSED: '已暂停', COMPLETED: '已完成' }
  statusText.value = map[s] || s || ''
})

function formatTime(sec) {
  if (!sec && sec !== 0) return '00:00:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
}

async function pollState() {
  try {
    state.value = await api.getState()
    foupsData.value = await api.getFoups()
    robotsData.value = await api.getRobots()
    if (!deviceConfig.value) deviceConfig.value = await api.getDeviceConfig()
    if (!jobConfig.value) {
      try { jobConfig.value = await api.getJobConfig() } catch (e) {}
    }
    if (state.value?.recentEvents?.length) {
      events.value.push(...state.value.recentEvents)
      if (events.value.length > 500) events.value = events.value.slice(-500)
      await nextTick()
      if (logRef.value) logRef.value.scrollTop = logRef.value.scrollHeight
    }
    // Auto-generate report on completion
    if (state.value?.status === 'COMPLETED' && !reportGenerated.value) {
      reportGenerated.value = true
      try { await api.generateReport() } catch (e) {}
    }
  } catch (e) { /* ignore */ }
}

async function pollGantt() {
  if (!showGantt.value) return
  try {
    ganttData.value = await api.getGantt()
  } catch (e) { /* ignore */ }
}

async function onStart() {
  await api.start()
  await pollState()
  await pollGantt()
}

async function onStartJob(cjId) {
  await api.start(cjId)
  await pollState()
  await pollGantt()
}

async function onPause() {
  await api.pause()
}

async function onStep() {
  const result = await api.step()
  state.value = result
  if (result?.recentEvents?.length) {
    events.value.push(...result.recentEvents)
    await nextTick()
    if (logRef.value) logRef.value.scrollTop = logRef.value.scrollHeight
  }
  await pollGantt()
}

async function onReset() {
  await api.reset()
  events.value = []
  ganttData.value = []
  reportGenerated.value = false
  await pollState()
}

async function onSpeedChange(val) {
  speed.value = val
  await api.setSpeed(val)
}

onMounted(() => {
  pollState()
  pollTimer = setInterval(pollState, 500)
  ganttTimer = setInterval(pollGantt, 1000)
})

onUnmounted(() => {
  clearInterval(pollTimer)
  clearInterval(ganttTimer)
})
</script>

<style>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.app-header {
  background: #16213e;
  padding: 12px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 2px solid #0f3460;
}
.app-header h1 {
  font-size: 18px;
  color: #00d2ff;
}
.header-stats {
  display: flex;
  gap: 20px;
}
.stat {
  font-size: 13px;
  color: #b0b0b0;
}
.status-running { color: #4CAF50; font-weight: bold; }
.status-paused { color: #FF9800; font-weight: bold; }
.status-completed { color: #00d2ff; font-weight: bold; }
.status-idle { color: #888; }
.main-content {
  display: flex;
  flex: 1;
  min-height: 400px;
}
.left-panel {
  flex: 1;
  padding: 12px;
  overflow: auto;
}
.right-panel {
  width: 380px;
  border-left: 1px solid #333;
  display: flex;
  flex-direction: column;
}
.event-log {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.event-log h3 {
  padding: 8px 12px;
  background: #16213e;
  font-size: 14px;
  color: #00d2ff;
  border-bottom: 1px solid #333;
}
.log-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 11px;
  max-height: 400px;
}
.log-entry {
  padding: 2px 0;
  border-bottom: 1px solid #222;
  color: #a0d0a0;
}
.gantt-section {
  border-top: 2px solid #0f3460;
  max-height: 400px;
  overflow: auto;
  background: #111;
}
</style>
