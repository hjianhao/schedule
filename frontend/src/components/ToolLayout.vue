<template>
  <div class="tool-layout">
    <div v-if="!state" style="color:#888;text-align:center;padding:40px">加载设备状态中...</div>
    <svg v-else viewBox="0 0 1140 520" class="tool-svg">
      <defs>
        <filter id="glow"><feGaussianBlur stdDeviation="2"/><feMerge><feMergeNode in="SourceGraphic"/></feMerge></filter>
      </defs>

      <!-- ===== EFEM (Atmosphere) ===== -->
      <rect x="5" y="30" width="235" height="460" rx="8" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="6,3" />
      <text x="120" y="22" text-anchor="middle" fill="#888" font-size="10">EFEM (大气环境)</text>

      <!-- LoadPorts / FOUPs -->
      <g v-for="(name, fi) in ['LP1','LP2','LP3']" :key="name"
         :transform="`translate(15, ${60 + fi * 145})`">
        <rect width="100" height="130" rx="4" fill="#1a2a3a" stroke="#2196F3" stroke-width="1.5" />
        <text x="50" y="14" text-anchor="middle" fill="#64B5F6" font-size="9">{{ name }} (FOUP{{ fi + 1 }})</text>
        <g v-for="slot in getFoupSlots(fi)" :key="slot.id">
          <rect :x="20 + (slot.col) * 13" :y="22 + (slot.row) * 13"
                width="11" height="11" rx="1"
                :fill="slot.color" stroke="#333" stroke-width="0.5" />
        </g>
        <text x="50" y="124" text-anchor="middle" fill="#555" font-size="7">25 slots</text>
      </g>

      <!-- Aligner (inside EFEM) -->
      <g transform="translate(140, 130)">
        <rect width="55" height="30" rx="4"
              :fill="getChamberColor('ALIGNER')" stroke="#FF9800" stroke-width="1.5" />
        <text x="27" y="12" text-anchor="middle" fill="#FFB74D" font-size="7">Aligner</text>
        <text x="27" y="24" text-anchor="middle" fill="#fff" font-size="7">{{ getChamberWafer('ALIGNER') || '空' }}</text>
      </g>

      <!-- ATM Robot (inside EFEM) -->
      <g transform="translate(155, 230)">
        <circle r="25" fill="#1a2a3a" stroke="#FF9800" stroke-width="2" />
        <text y="-10" text-anchor="middle" fill="#FF9800" font-size="8" font-weight="bold">ATM Robot</text>
        <text y="1" text-anchor="middle" fill="#aaa" font-size="6">{{ getRobotStateTxt('ATM1') }}</text>
        <g v-if="getRobot('ATM1')" :transform="`rotate(${getArmAngleATM()})`">
          <line x1="0" y1="0" :x2="getArmLengthATM()" y2="0" stroke="#FF9800" stroke-width="3" stroke-linecap="round" />
          <circle r="5" :cx="getArmLengthATM()" cy="0" fill="#FF9800" stroke="#fff" stroke-width="1" filter="url(#glow)" />
          <text v-if="getRobot('ATM1')?.armWaferId" :x="getArmLengthATM()" y="12"
                text-anchor="middle" fill="#FFD54F" font-size="7">{{ getRobot('ATM1')?.armWaferId }}</text>
        </g>
      </g>

      <!-- Connections: ATM to LL -->
      <g stroke="#555" stroke-width="1" fill="none" stroke-dasharray="4,3">
        <line x1="180" y1="220" x2="245" y2="195" />
        <line x1="180" y1="240" x2="245" y2="305" />
      </g>

      <!-- BatchLoadLocks -->
      <g v-for="ll in ['LL1','LL2']" :key="ll"
         :transform="`translate(250, ${ll === 'LL1' ? 160 : 275})`">
        <rect width="80" height="55" rx="6"
              :fill="getChamberColor(ll)" stroke="#00BCD4" stroke-width="1.5" />
        <text x="40" y="14" text-anchor="middle" fill="#aaa" font-size="8">{{ ll }} (BLL)</text>
        <text x="40" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">
          {{ getChamberStateText(ll) }}
        </text>
        <text x="40" y="44" text-anchor="middle" fill="#FFD54F" font-size="9">
          {{ getLLWaferCount(ll) }}片
        </text>
      </g>

      <!-- Connections: LL to TM1 -->
      <g stroke="#334" stroke-width="1.5" fill="none">
        <line x1="330" y1="185" x2="360" y2="215" />
        <line x1="330" y1="305" x2="360" y2="245" />
      </g>

      <!-- Vacuum boundary -->
      <rect x="345" y="35" width="10" height="450" rx="2" fill="#0f3460" stroke="#00BCD4" stroke-width="1" />
      <text x="350" y="25" text-anchor="middle" fill="#00BCD4" font-size="8">真空</text>

      <!-- TM1 -->
      <g transform="translate(380, 230)">
        <circle r="35" fill="#1a2a3a" stroke="#00BCD4" stroke-width="2" />
        <text y="-12" text-anchor="middle" fill="#00BCD4" font-size="9" font-weight="bold">TM1</text>
        <text y="2" text-anchor="middle" fill="#aaa" font-size="7">{{ getRobotStateTxt('Robot1') }}</text>
        <!-- Single arm -->
        <g v-if="getRobot('Robot1')" :transform="`rotate(${getArmAngle('Robot1')})`">
          <line x1="0" y1="0" :x2="getArmLength('Robot1')" y2="0" stroke="#FF5722" stroke-width="3" stroke-linecap="round" />
          <circle r="6" :cx="getArmLength('Robot1')" cy="0" fill="#FF5722" stroke="#fff" stroke-width="1" filter="url(#glow)" />
          <text v-if="getRobot('Robot1')?.armWaferId" :x="getArmLength('Robot1')" y="12"
                text-anchor="middle" fill="#FFD54F" font-size="7">{{ getRobot('Robot1')?.armWaferId }}</text>
        </g>
      </g>

      <!-- PreClean Chambers -->
      <g v-for="(pc, i) in ['PreClean1','PreClean2']" :key="pc"
         :transform="`translate(335, ${i === 0 ? 50 : 365})`">
        <rect width="90" height="45" rx="6"
              :fill="getChamberColor(pc)" stroke="#FF9800" stroke-width="1.5" />
        <text x="45" y="14" text-anchor="middle" fill="#FFB74D" font-size="9">{{ pc }}</text>
        <text x="45" y="27" text-anchor="middle" fill="#fff" font-size="10">{{ getChamberStateText(pc) }}</text>
        <text v-if="getChamberWafer(pc)" x="45" y="40" text-anchor="middle" fill="#FFD54F" font-size="9">{{ getChamberWafer(pc) }}</text>
        <rect v-if="getChamberProgress(pc) > 0" x="5" y="42" :width="80*getChamberProgress(pc)" height="2" rx="1" fill="#FF9800" />
      </g>

      <!-- PassThroughs (2 slots each) -->
      <g v-for="(pt, i) in ['PT1','PT2']" :key="pt">
        <g v-for="s in [0, 1]" :key="pt+'_S'+s"
           :transform="`translate(485, ${i === 0 ? 170 + s * 42 : 245 + s * 42})`">
          <rect width="65" height="34" rx="4"
                :fill="getChamberColor(pt+'_S'+s)"
                :stroke="isCoolingStation(pt, s) ? '#00BCD4' : '#FFEB3B'"
                stroke-width="1.5" />
          <text x="32" y="12" text-anchor="middle"
                :fill="isCoolingStation(pt, s) ? '#80DEEA' : '#FFF176'" font-size="8">{{ pt }}_S{{ s }}</text>
          <text x="32" y="26" text-anchor="middle" fill="#fff" font-size="9">{{ getChamberWafer(pt+'_S'+s) || '空' }}</text>
          <text v-if="isCoolingStation(pt, s)" x="32" y="34" text-anchor="middle" fill="#00BCD4" font-size="7">❄</text>
        </g>
      </g>

      <!-- TM2 -->
      <g transform="translate(620, 230)">
        <circle r="42" fill="#1a2a3a" stroke="#E91E63" stroke-width="2" />
        <text y="-16" text-anchor="middle" fill="#E91E63" font-size="10" font-weight="bold">TM2</text>
        <text y="-2" text-anchor="middle" fill="#aaa" font-size="8">{{ getRobotStateTxt('Robot2') }}</text>
        <g v-if="getRobot('Robot2')" :transform="`rotate(${getArmAngle('Robot2')})`">
          <line x1="0" y1="0" :x2="getArmLength('Robot2')" y2="0" stroke="#E91E63" stroke-width="3" stroke-linecap="round" />
          <circle r="6" :cx="getArmLength('Robot2')" cy="0" fill="#E91E63" stroke="#fff" stroke-width="1" filter="url(#glow)" />
          <text v-if="getRobot('Robot2')?.armWaferId" :x="getArmLength('Robot2')" y="12"
                text-anchor="middle" fill="#FFD54F" font-size="7">{{ getRobot('Robot2')?.armWaferId }}</text>
        </g>
      </g>

      <!-- EPI Chambers -->
      <g v-for="(epi, i) in ['EPI1','EPI2','EPI3','EPI4']" :key="epi"
         :transform="`translate(710, ${40 + i * 95})`">
        <rect width="95" height="50" rx="6"
              :fill="getChamberColor(epi)" stroke="#4CAF50" stroke-width="1.5" />
        <text x="47" y="14" text-anchor="middle" fill="#81C784" font-size="9">{{ epi }}</text>
        <text x="47" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">{{ getChamberStateText(epi) }}</text>
        <text v-if="getChamberWafer(epi)" x="47" y="42" text-anchor="middle" fill="#FFD54F" font-size="9">{{ getChamberWafer(epi) }}</text>
        <rect v-if="getChamberProgress(epi) > 0" x="5" y="46" :width="85*getChamberProgress(epi)" height="3" rx="1" fill="#4CAF50" />
      </g>

      <!-- Legend -->
      <g transform="translate(820, 435)">
        <text x="0" y="10" fill="#888" font-size="9">FOUP: </text>
        <rect x="42" y="2" width="10" height="10" rx="1" fill="#555" /><text x="56" y="11" fill="#888" font-size="8">未处理</text>
        <rect x="100" y="2" width="10" height="10" rx="1" fill="#1a1a2e" stroke="#333"/><text x="114" y="11" fill="#888" font-size="8">已取走</text>
        <rect x="158" y="2" width="10" height="10" rx="1" fill="#4CAF50"/><text x="172" y="11" fill="#888" font-size="8">已完成</text>
      </g>
    </svg>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ state: Object, foups: Object, robots: Array, passthroughs: Array, robotTimes: Object })

const atmAngles = { 'LP1': -131, 'LP2': 156, 'LP3': 116, 'ALIGNER': -82, 'LL1': -18, 'LL2': 28 }

function isCoolingStation(ptId, slotIndex) {
  if (!props.passthroughs) return false
  const pt = props.passthroughs.find(p => p.id === ptId)
  return pt?.coolingStationSlot === slotIndex
}

function getChamberState(id) {
  return props.state?.chambers?.[id]?.state || 'IDLE'
}
function getChamberColor(id) {
  const s = getChamberState(id)
  const map = { IDLE: '#2a3a4a', PROCESSING: '#1b5e20', DONE: '#e65100',
    PUMPING: '#0d47a1', VENTING: '#4a148c', READY: '#006064', LOADING: '#3e2723', UNLOADING: '#3e2723',
    CLEANING: '#FF5722', PURGING: '#9C27B0' }
  return map[s] || '#2a3a4a'
}
function getChamberStateText(id) {
  const s = getChamberState(id)
  const c = props.state?.chambers?.[id]
  const map = { IDLE: '空闲', PROCESSING: '处理中', DONE: '完成', PUMPING: '抽真空', VENTING: '充气', READY: '就绪', LOADING: '装载', UNLOADING: '卸载', CLEANING: '清洗', PURGING: '吹扫' }
  let t = map[s] || s
  if (c?.remainingTimeSec > 0) t += ` ${c.remainingTimeSec}s`
  return t
}
function getChamberWafer(id) { return props.state?.chambers?.[id]?.waferId || null }
function getChamberProgress(id) {
  const c = props.state?.chambers?.[id]
  if (!c || c.totalTimeSec <= 0) return 0
  return Math.max(0, Math.min(1, 1 - c.remainingTimeSec / c.totalTimeSec))
}
function getLLWaferCount(id) {
  return props.state?.chambers?.[id]?.waferCount || 0
}
function getPTDirection(id) {
  const w = props.state?.wafers?.find(w => w.id === getChamberWafer(id))
  if (!w) return ''
  return w.flowStep <= 8 ? '→' : '←'
}

function getRobot(id) { return (props.robots || []).find(r => r.id === id) || null }
function getRobotStateTxt(id) {
  const r = getRobot(id)
  if (!r) return '离线'
  return r.busy ? '忙碌 ' + r.currentAction : '空闲'
}
function getArmLength(id) {
  const r = getRobot(id)
  if (!r || !r.busy) return 30
  return 35
}

const tm1Angles = { 'LL1': -155, 'LL2': 141, 'PreClean1': -90, 'PreClean2': 90,
                    'PT1_S0': -17, 'PT1_S1': 0, 'PT2_S0': 13, 'PT2_S1': 28 }
const tm2Angles = { 'EPI1': -50, 'EPI2': -27, 'EPI3': 10, 'EPI4': 41,
                    'PT1_S0': -157, 'PT1_S1': 180, 'PT2_S0': 163, 'PT2_S1': 144 }

function getArmPhase(r) {
  if (!r || !r.busy) return ''
  const rt = props.robotTimes?.[r.id]
  const totalTime = rt?.total || 15
  const remaining = r.remainingTimeSec || 0
  return remaining > totalTime / 2 ? (r.sourceChamber || '') : (r.targetChamber || '')
}

function getArmAngle(id) {
  const r = getRobot(id)
  if (!r) return 0
  const phase = getArmPhase(r)
  const map = id === 'Robot1' ? tm1Angles : tm2Angles
  return map[phase] || 0
}

function getArmAngleATM() {
  const r = getRobot('ATM1')
  if (!r || !r.busy) return 0
  const phase = getArmPhase(r)
  return atmAngles[phase] || 0
}

function getArmLengthATM() {
  const r = getRobot('ATM1')
  if (!r || !r.busy) return 20
  return 28
}

function getFoupSlots(fi) {
  const slots = []
  for (let row = 0; row < 5; row++) {
    for (let col = 0; col < 5; col++) {
      const si = row * 5 + col
      const key = fi + '_' + si
      const fs = (props.foups || {})[key]
      let color = '#1a1a2e' // default dark (no wafer assigned)
      if (fs) {
          if (fs.state === 'FILLED') color = '#555'
          else if (fs.state === 'EMPTY') color = '#1a1a2e'
          else if (fs.state === 'DONE') color = '#4CAF50'
      }
      slots.push({ id: key, row, col, color, fi, si })
    }
  }
  return slots
}
</script>

<style scoped>
.tool-layout { background: #111827; border-radius: 8px; padding: 8px; }
.tool-svg { width: 100%; height: auto; max-height: 500px; }
</style>
