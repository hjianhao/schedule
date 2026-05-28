<template>
  <div class="control-panel">
    <div class="controls">
      <button @click="$emit('reset')" class="btn btn-reset">
        <span class="icon">↺</span> 重置
      </button>
      <button
        v-if="status !== 'RUNNING'"
        @click="handleStart"
        class="btn btn-start"
        :disabled="status === 'COMPLETED'"
      >
        <span class="icon">▶</span> 启动
      </button>
      <button v-else @click="$emit('pause')" class="btn btn-pause">
        <span class="icon">⏸</span> 暂停
      </button>
      <button @click="$emit('step')" class="btn btn-step" :disabled="status === 'RUNNING' || status === 'COMPLETED'">
        <span class="icon">⏭</span> 单步
      </button>
      <button @click="$emit('show-gantt')" class="btn btn-gantt">
        <span class="icon">▤</span> 甘特图
      </button>
    </div>
    <div class="job-select" v-if="controlJobs && controlJobs.length > 0">
      <label>Job:</label>
      <select v-model="selectedCJ" @change="onCJChange">
        <option value="">-- 默认 --</option>
        <option v-for="cj in controlJobs" :key="cj.id" :value="cj.id">{{ cj.name }} ({{ cj.totalWaferCount }}片)</option>
      </select>
    </div>
    <div class="speed-control">
      <label>速度: {{ speed }}x</label>
      <input
        type="range"
        min="1"
        max="100"
        :value="speed"
        @input="$emit('speed-change', parseInt($event.target.value))"
      />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  status: String,
  speed: Number,
  controlJobs: Array,
})

const emit = defineEmits(['reset', 'start', 'start-job', 'pause', 'step', 'speed-change', 'show-gantt'])

const selectedCJ = ref('')

function handleStart() {
  if (selectedCJ.value) {
    emit('start-job', selectedCJ.value)
  } else {
    emit('start')
  }
}

function onCJChange() {
  // CJ selection changed
}
</script>

<style scoped>
.control-panel {
  background: #16213e;
  padding: 8px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #0f3460;
}
.controls {
  display: flex;
  gap: 8px;
}
.btn {
  padding: 6px 16px;
  border: 1px solid #444;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: all 0.2s;
  color: #e0e0e0;
  background: #2a2a4a;
}
.btn:hover:not(:disabled) { background: #3a3a5a; }
.btn:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-start { border-color: #4CAF50; color: #4CAF50; }
.btn-start:hover:not(:disabled) { background: #1b5e20; }
.btn-pause { border-color: #FF9800; color: #FF9800; }
.btn-pause:hover { background: #e65100; }
.btn-reset { border-color: #f44336; color: #f44336; }
.btn-reset:hover { background: #b71c1c; }
.btn-step { border-color: #2196F3; color: #2196F3; }
.btn-step:hover:not(:disabled) { background: #0d47a1; }
.btn-gantt { border-color: #00d2ff; color: #00d2ff; }
.btn-gantt:hover { background: #004d66; }
.icon { font-size: 12px; }
.speed-control {
  display: flex;
  align-items: center;
  gap: 10px;
}
.speed-control label {
  font-size: 13px;
  color: #aaa;
  min-width: 60px;
}
.speed-control input[type="range"] {
  width: 150px;
  accent-color: #00d2ff;
}
</style>
