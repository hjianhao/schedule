<template>
  <div class="gantt-container">
    <div class="gantt-header">
      <h3>调度甘特图</h3>
      <div class="gantt-controls">
        <label>视图:</label>
        <select v-model="viewMode" class="view-select">
          <option value="wafer">Wafer视图</option>
          <option value="chamber">腔室视图</option>
        </select>
        <label>缩放:</label>
        <input type="range" min="0.1" max="5" step="0.1" v-model.number="scale" />
        <span>{{ scale.toFixed(1) }}x</span>
        <div class="gantt-legend">
          <span class="legend-item"><span class="dot" style="background:#2196F3"></span>LoadLock</span>
          <span class="legend-item"><span class="dot" style="background:#FF9800"></span>PreClean</span>
          <span class="legend-item"><span class="dot" style="background:#FFEB3B"></span>PassThrough</span>
          <span class="legend-item"><span class="dot" style="background:#4CAF50"></span>EPI</span>
          <span class="legend-item"><span class="dot" style="background:#E91E63"></span>PT返回</span>
          <span class="legend-item"><span class="dot" style="background:#9C27B0"></span>LL返回</span>
        </div>
      </div>
    </div>
    <div class="gantt-body">
      <div class="gantt-labels">
        <div class="label-header">{{ viewMode === 'wafer' ? 'Wafer' : '腔室' }}</div>
        <div v-for="(row, i) in rowIds" :key="row" class="label-row"
             :class="{ 'epi-row': chamberTypes[row] === 'EPI' }">
          {{ row }}
          <span v-if="viewMode === 'chamber' && chamberStats[row]" class="util-badge">
            {{ chamberStats[row] }}%
          </span>
        </div>
      </div>
      <div class="gantt-scroll" ref="scrollRef">
        <svg :width="svgWidth" :height="svgHeight" class="gantt-svg">
          <!-- Time axis -->
          <g class="time-axis">
            <line x1="0" :y1="20" :x2="svgWidth" :y2="20" stroke="#333" />
            <g v-for="tick in timeTicks" :key="tick.x">
              <line :x1="tick.x" y1="15" :x2="tick.x" y2="20" stroke="#555" />
              <text :x="tick.x" y="12" fill="#888" font-size="9" text-anchor="middle">{{ tick.label }}</text>
              <line :x1="tick.x" :y1="20" :x2="tick.x" :y2="svgHeight" stroke="#222" stroke-dasharray="2,4" />
            </g>
          </g>
          <!-- Rows background -->
          <g class="rows">
            <rect v-for="(row, i) in rowIds" :key="'bg'+row"
                  x="0" :y="22 + i * rowHeight" :width="svgWidth" :height="rowHeight"
                  :fill="i % 2 === 0 ? '#1a1a2e' : '#16213e'" />
          </g>
          <!-- Gantt bars -->
          <g class="bars">
            <g v-for="(bar, idx) in renderedBars" :key="idx">
              <rect :x="bar.x" :y="bar.y" :width="Math.max(bar.w, 2)" :height="bar.h"
                    :fill="bar.color" rx="2" :opacity="bar.opacity || 0.85">
                <title>{{ bar.title }}</title>
              </rect>
              <text v-if="bar.w > 30" :x="bar.x + bar.w/2" :y="bar.y + bar.h/2 + 3"
                    text-anchor="middle" :fill="bar.textColor || '#fff'" font-size="7">{{ bar.label }}</text>
            </g>
          </g>
          <!-- Current time marker -->
          <line :x1="currentTimeX" y1="0" :x2="currentTimeX" :y2="svgHeight"
                stroke="#ff0040" stroke-width="1.5" stroke-dasharray="4,2" />
        </svg>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'

const props = defineProps({
  data: { type: Array, default: () => [] },
  currentTime: { type: Number, default: 0 },
  robotTimes: { type: Object, default: () => ({}) }
})

const outRobot = { 'PRECLEAN': 'Robot1', 'PASSTHROUGH': 'Robot2', 'PT_RETURN': 'Robot1', 'EPI': 'Robot2' }

function getXfer(entry) {
  const map = { 
    'PRECLEAN': ['Robot1', 'PRECLEAN_TO_PT'], 
    'PASSTHROUGH': ['Robot2', 'PT_TO_EPI'],
    'PT_RETURN': ['Robot1', 'PT_TO_LL'], 
    'EPI': ['Robot2', 'EPI_TO_PT'] 
  }
  const [rId, opKey] = map[entry.type] || ['Robot1', null]
  const rt = props.robotTimes[rId]
  if (!rt) return 0
  if (opKey && rt.ops?.[opKey]) return rt.ops[opKey].xfer
  return rt.xfer
}

const scale = ref(0.5)
const viewMode = ref('chamber')
const scrollRef = ref(null)
const rowHeight = 22

const waferIds = computed(() => {
  const ids = new Set()
  props.data.forEach(e => ids.add(e.waferId))
  return [...ids].sort((a, b) => {
    const na = parseInt(a.replace(/\D/g, ''))
    const nb = parseInt(b.replace(/\D/g, ''))
    return na - nb
  })
})

const chamberIds = computed(() => {
  const ids = new Set()
  props.data.forEach(e => ids.add(e.location))
  return [...ids].sort((a, b) => {
    const order = {
      'FOUP': 0, 'LL1': 1, 'LL2': 2, 'ALIGNER': 3,
      'PreClean1': 4, 'PreClean2': 5,
      'PT1_S0': 6, 'PT1_S1': 7, 'PT2_S0': 8, 'PT2_S1': 9,
      'EPI1': 10, 'EPI2': 11, 'EPI3': 12, 'EPI4': 13
    }
    return (order[a] ?? 99) - (order[b] ?? 99)
  })
})

const rowIds = computed(() => viewMode.value === 'wafer' ? waferIds.value : chamberIds.value)

const chamberTypes = computed(() => {
  const map = {}
  chamberIds.value.forEach(id => {
    const entries = props.data.filter(e => e.location === id)
    if (entries.length > 0) map[id] = entries[0].type
    else map[id] = ''
  })
  return map
})

const chamberStats = computed(() => {
  if (viewMode.value !== 'chamber') return {}
  const stats = {}
  const totalTime = Math.max(props.currentTime, 1)
  chamberIds.value.forEach(id => {
    const entries = props.data.filter(e => e.location === id)
    let occupiedTime = 0
    entries.forEach(e => {
      const endT = e.endTimeSec > 0 ? e.endTimeSec : props.currentTime
      occupiedTime += Math.max(0, endT - e.startTimeSec - getXfer(e))
    })
    if (id.startsWith('EPI') && entries.length > 0) {
      const firstIn = Math.min(...entries.map(e => e.startTimeSec))
      const lastOut = Math.max(...entries.map(e => e.endTimeSec > 0 ? e.endTimeSec : props.currentTime))
      const denominator = lastOut - firstIn
      stats[id] = Math.min(100, Math.round((occupiedTime / denominator) * 100))
    } else {
      stats[id] = Math.min(100, Math.round((occupiedTime / totalTime) * 100))
    }
  })
  return stats
})

const svgWidth = computed(() => Math.max(800, (props.currentTime + 60) * scale.value + 50))
const svgHeight = computed(() => Math.max(200, 22 + rowIds.value.length * rowHeight + 10))
const currentTimeX = computed(() => props.currentTime * scale.value)

const timeTicks = computed(() => {
  const ticks = []
  let interval = 60
  if (scale.value < 0.3) interval = 600
  else if (scale.value < 0.8) interval = 300
  else if (scale.value < 2) interval = 60
  else interval = 30
  for (let t = 0; t <= props.currentTime + 120; t += interval) {
    const x = t * scale.value
    const m = Math.floor(t / 60)
    const s = t % 60
    ticks.push({ x, label: `${m}:${String(s).padStart(2, '0')}` })
  }
  return ticks
})

const renderedBars = computed(() => {
  const bars = []
  const rowIndex = {}
  rowIds.value.forEach((id, i) => rowIndex[id] = i)

  if (viewMode.value === 'wafer') {
    props.data.forEach(entry => {
      const ri = rowIndex[entry.waferId]
      if (ri === undefined) return
      const x = entry.startTimeSec * scale.value
      const endTime = entry.endTimeSec > 0 ? entry.endTimeSec : props.currentTime
      const w = Math.max(0, (endTime - entry.startTimeSec - getXfer(entry)) * scale.value)
      bars.push({
        x, y: 22 + ri * rowHeight + 2, w, h: rowHeight - 4,
        color: entry.color || '#666',
        label: entry.location,
        title: `${entry.waferId} @ ${entry.location} (dwell ${Math.max(0, endTime - entry.startTimeSec - getXfer(entry))}s)`
      })
    })
  } else {
    const colorMap = {
      'LOADLOCK': '#2196F3', 'PRECLEAN': '#FF9800', 'PASSTHROUGH': '#FFEB3B',
      'EPI': '#4CAF50', 'PT_RETURN': '#E91E63', 'LOADLOCK_RET': '#9C27B0',
      'CLEAN': '#FF5722', 'PURGE': '#AB47BC', 'COMPLETE': '#4CAF50'
    }
    props.data.forEach(entry => {
      const ri = rowIndex[entry.location]
      if (ri === undefined) return
      const x = entry.startTimeSec * scale.value
      const endTime = entry.endTimeSec > 0 ? entry.endTimeSec : props.currentTime
      const w = Math.max(0, (endTime - entry.startTimeSec - getXfer(entry)) * scale.value)
      const isEpi = entry.type === 'EPI'
      bars.push({
        x, y: 22 + ri * rowHeight + 2, w, h: rowHeight - 4,
        color: isEpi ? '#2E7D32' : (colorMap[entry.type] || '#666'),
        opacity: isEpi ? 1.0 : 0.6,
        textColor: isEpi ? '#fff' : '#ccc',
        label: entry.waferId,
        title: `${entry.waferId} @ ${entry.location} [${entry.startTimeSec}s - ${endTime}s]`
      })
    })
  }
  return bars
})

watch(() => props.currentTime, async () => {
  await nextTick()
  if (scrollRef.value) {
    const target = currentTimeX.value - scrollRef.value.clientWidth + 100
    if (target > scrollRef.value.scrollLeft) {
      scrollRef.value.scrollLeft = target
    }
  }
})
</script>

<style scoped>
.gantt-container { background: #111; }
.gantt-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 16px; background: #16213e; border-bottom: 1px solid #333; flex-wrap: wrap; gap: 6px;
}
.gantt-header h3 { font-size: 14px; color: #00d2ff; }
.gantt-controls { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #aaa; flex-wrap: wrap; }
.view-select {
  background: #1a1a2e; color: #00d2ff; border: 1px solid #333; border-radius: 3px;
  padding: 2px 6px; font-size: 12px;
}
.gantt-controls input[type="range"] { width: 100px; accent-color: #00d2ff; }
.gantt-legend { display: flex; gap: 10px; margin-left: 16px; }
.legend-item { display: flex; align-items: center; gap: 3px; font-size: 10px; }
.dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.gantt-body { display: flex; max-height: 350px; }
.gantt-labels { min-width: 90px; background: #16213e; border-right: 1px solid #333; flex-shrink: 0; overflow: hidden; }
.label-header { height: 22px; line-height: 22px; padding: 0 8px; font-size: 10px; color: #888; border-bottom: 1px solid #333; }
.label-row {
  height: 22px; line-height: 22px; padding: 0 6px; font-size: 10px; color: #aaa;
  border-bottom: 1px solid #1a1a2e; display: flex; justify-content: space-between; align-items: center;
}
.epi-row { color: #81C784; font-weight: bold; }
.util-badge {
  font-size: 8px; background: #1b5e20; color: #4CAF50; padding: 0 4px; border-radius: 3px;
  min-width: 28px; text-align: center;
}
.gantt-scroll { flex: 1; overflow-x: auto; overflow-y: auto; }
.gantt-svg { display: block; }
</style>
