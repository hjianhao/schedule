#!/usr/bin/env python3
"""Generate simulation report HTML with statistics and Gantt chart."""

import json
import urllib.request
import os

API = "http://localhost:8080/api"

def fetch(endpoint):
    with urllib.request.urlopen(f"{API}{endpoint}") as resp:
        return json.loads(resp.read())

def parse_time(event_str):
    try:
        time_part = event_str.split(']')[0].strip('[')
        h, m, s = map(int, time_part.split(':'))
        return h * 3600 + m * 60 + s
    except:
        return None

def wafer_sort_key(wid):
    """Parse W1.25 → (1, 25); old format W1 → (0, 1)."""
    if '.' in wid:
        parts = wid[1:].split('.')
        return (int(parts[0]), int(parts[1]))
    return (0, int(wid[1:]))

def format_time(sec):
    if sec < 0: sec = 0
    h = sec // 3600
    m = (sec % 3600) // 60
    s = sec % 60
    if h > 0: return f"{h}h{m}m{s}s"
    if m > 0: return f"{m}m{s}s"
    return f"{s}s"

def format_short(sec):
    if sec < 0: return "-"
    if sec == 0: return "0s"
    m, s = divmod(sec, 60)
    if m > 0: return f"{m}m{s}s"
    return f"{s}s"

# PT slot colors: cooling → blue, buffer → yellow (fwd/ret shades)
def get_pt_slot_colors(device_config):
    cooling_slots = set()
    for pt in device_config.get('passthroughs', []):
        if pt.get('coolingStationSlot') is not None:
            cooling_slots.add(f"{pt['id']}_S{pt['coolingStationSlot']}")
    result = {}
    for ch in ['PT1_S0', 'PT1_S1', 'PT2_S0', 'PT2_S1']:
        if ch in cooling_slots:
            result[ch] = {'fwd': '#42A5F5', 'ret': '#1565C0'}  # blue
        else:
            result[ch] = {'fwd': '#FFEB3B', 'ret': '#F9A825'}  # yellow
    return result, cooling_slots

loc_colors = {
    'LL1': '#2196F3', 'LL2': '#2196F3',
    'PreClean1': '#FF9800', 'PreClean2': '#FF9800',
    'EPI1': '#4CAF50', 'EPI2': '#66BB6A', 'EPI3': '#43A047', 'EPI4': '#388E3C',
    'CLEAN': '#FF5722',
}

def gantt_color(entry, slot_colors):
    loc = entry['location']
    etype = entry['type']
    if loc in slot_colors:
        return slot_colors[loc]['ret'] if etype == 'PT_RETURN' else slot_colors[loc]['fwd']
    if loc in loc_colors:
        return loc_colors[loc]
    if etype == 'LOADLOCK_RET':
        return '#9C27B0'
    if etype == 'LOADLOCK':
        return '#2196F3'
    return '#666'

def violation_table(gantt, schedule, get_total_xfer, am_config=None):
    """Compute max dwell violations and 1X Clean gap violations from gantt data."""
    stats = {}
    limits = {'PreClean': 120, 'EPI': 100, 'PT': 300}
    recipes = schedule.get('recipes', {})
    timing = schedule.get('timing', {})

    # --- Dwell violations ---
    for e in gantt:
        wid = e.get('waferId', '')
        if not wid.startswith('W'): continue
        etype = e.get('type', '')
        loc = e.get('location', '')
        dur = max(0, (e.get('endTimeSec', 0) or 0) - e['startTimeSec'])
        xfer = get_total_xfer(etype)  # full robot time (pick+rotate+place)

        dwell = 0
        ctype = None

        if etype == 'PRECLEAN':
            ctype = 'PreClean'
            proc = recipes.get('PRECLEAN', {}).get('avgProcessTimeSec', 280)
            dwell = max(0, dur - xfer - proc)
        elif etype == 'EPI':
            ctype = 'EPI'
            proc = recipes.get('EPI', {}).get('avgProcessTimeSec', 2120)
            dwell = max(0, dur - xfer - proc)
        elif etype == 'PASSTHROUGH':
            ctype = 'PT'
            dwell = max(0, dur - xfer)  # no process, pure transit dwell
        elif etype == 'PT_RETURN':
            ctype = 'PT'
            cool = timing.get('coolingStationCoolTimeSec', 60)
            dwell = max(0, dur - xfer - cool)

        if ctype is None: continue
        limit = limits[ctype]
        if dwell > limit:
            if ctype not in stats:
                stats[ctype] = {'count': 0, 'max': 0, 'max_wafer': '', 'max_chamber': ''}
            stats[ctype]['count'] += 1
            if dwell > stats[ctype]['max']:
                stats[ctype]['max'] = dwell
                stats[ctype]['max_wafer'] = wid
                stats[ctype]['max_chamber'] = loc

    # --- 1X Clean gap violations (exclude OnLoadClean — startup overhead) ---
    clean_gap_stat = {'count': 0, 'max': 0, 'max_chamber': '', 'max_wafer': ''}
    gap_limit = 0
    if am_config:
        for task in am_config.get('tasks', []):
            if task.get('type') == 'PRE_PROCESS' and 'EPI' in str(task.get('appliesTo', '')):
                gap_limit = int(task.get('gapTimeSec', 0))
                break

    robot_pt_to_epi = get_total_xfer('PASSTHROUGH')

    chamber_entries = {}
    for e in gantt:
        loc = e.get('location', '')
        if loc not in chamber_entries: chamber_entries[loc] = []
        chamber_entries[loc].append(e)
    for loc, entries in chamber_entries.items():
        entries.sort(key=lambda x: x['startTimeSec'])
        clean_count = 0
        for i in range(len(entries) - 1):
            if entries[i].get('type') == 'CLEAN' and entries[i+1].get('type') == 'EPI':
                clean_count += 1
                if clean_count == 1: continue  # skip OnLoadClean gap (startup overhead)
                clean_end = entries[i]['endTimeSec'] if entries[i]['endTimeSec'] > 0 else entries[i]['startTimeSec']
                wafer_place = entries[i+1]['startTimeSec']
                gap = max(0, wafer_place - clean_end - robot_pt_to_epi)
                if gap > gap_limit:
                    clean_gap_stat['count'] += 1
                    if gap > clean_gap_stat['max']:
                        clean_gap_stat['max'] = gap
                        clean_gap_stat['max_chamber'] = loc
                        clean_gap_stat['max_wafer'] = entries[i+1].get('waferId', '')

    rows = ""
    for ctype in ['PreClean', 'EPI', 'PT']:
        limit = limits[ctype]
        s = stats.get(ctype)
        if s:
            rows += f"<tr><td>{ctype} Dwell</td><td>{limit}s</td><td style='color:#FF9800'>{s['count']}</td><td style='color:#E91E63'>{format_time(s['max'])} ({s['max']}s)</td><td>{s['max_wafer']} @ {s['max_chamber']}</td></tr>"
        else:
            rows += f"<tr><td>{ctype} Dwell</td><td>{limit}s</td><td style='color:#4CAF50'>0 ✓</td><td>-</td><td>-</td></tr>"

    # 1X Clean gap row
    if clean_gap_stat['count'] > 0:
        rows += f"<tr><td>1X Clean Gap</td><td>{gap_limit}s</td><td style='color:#FF9800'>{clean_gap_stat['count']}</td><td style='color:#E91E63'>{format_time(clean_gap_stat['max'])} ({clean_gap_stat['max']}s)</td><td>{clean_gap_stat['max_wafer']} @ {clean_gap_stat['max_chamber']}</td></tr>"
    else:
        rows += f"<tr><td>1X Clean Gap</td><td>{gap_limit}s</td><td style='color:#4CAF50'>0 ✓</td><td>-</td><td>-</td></tr>"

    return f"""<table>
<tr><th>约束类型</th><th>限制值</th><th>违反次数</th><th>最大违反值</th><th>最大违反位置</th></tr>
{rows}
</table>"""
def wafer_history_options(sorted_ids):
    return '\n'.join(f'<option value="{w}">{w}</option>' for w in sorted_ids)

def wafer_history_js(sorted_ids, wafers, gantt, op_map, sim_time):
    """Generate JS map of wafer_id -> HTML table."""
    out = []
    out.append('{')
    for wid in sorted_ids:
        rows = wafer_history_html(wid, wafers, gantt, op_map, sim_time)
        if not rows:
            out.append(f'"{wid}": "无数据",')
            continue
        table = '<table style=width:100%;background:#16213e;border-radius:8px;overflow:hidden><tr style=background:#0f3460><th>时间</th><th>+间隔</th><th>步骤</th><th>详情</th><th>Pick</th><th>Rot</th><th>Place</th><th>总传输</th><th>处理</th><th>驻留</th></tr>' + rows + '</table>'
        out.append(f'"{wid}": `{table}`,')
    out.append('}')
    return '\n'.join(out)
    rows = ''
    for tm in device.get('transferModules', []):
        for rob in tm.get('robots', []):
            ops = rob.get('operations', {})
            for op_key, op in ops.items():
                p = op.get('pickTimeSec', 0)
                r = op.get('rotateTimeSec', 0)
                pl = op.get('placeTimeSec', 0)
                rows += f'<tr><td>{rob["id"]} {op_key}</td><td>pick {p}s + rot {r}s + place {pl}s = {p+r+pl}s</td><td>-</td></tr>'
    # ATM robot ops
    atm = device.get('efem', {}).get('atmRobot', {})
    for op_key in ['foupToAligner', 'alignerToLL']:
        op = atm.get(op_key, {})
        if op:
            p = op.get('pickTimeSec', 0); r = op.get('rotateTimeSec', 0); pl = op.get('placeTimeSec', 0)
            rows += f'<tr><td>ATM1 {op_key}</td><td>pick {p}s + rot {r}s + place {pl}s = {p+r+pl}s</td><td>-</td></tr>'
    return rows

def wafer_history_data(events, gantt, device, schedule):
    """Build per-wafer step timeline."""
    wafers = {}
    for e in gantt:
        wid = e.get('waferId', '')
        if not wid.startswith('W'): continue
        if wid not in wafers: wafers[wid] = []
        wafers[wid].append(e)
    for w in wafers:
        wafers[w].sort(key=lambda x: x['startTimeSec'])

    # Op timing map
    op_map = {}
    for tm in device.get('transferModules', []):
        for rob in tm.get('robots', []):
            for k, v in (rob.get('operations') or {}).items():
                op_map[k] = v
    atm = device.get('efem', {}).get('atmRobot', {})
    for k in ['foupToAligner', 'alignerToLL']:
        if atm.get(k): op_map[k] = atm[k]

    # Recipe times
    recipes = schedule.get('recipes', {})
    pc_time = recipes.get('PRECLEAN', {}).get('avgProcessTimeSec', 280)
    epi_time = recipes.get('EPI', {}).get('avgProcessTimeSec', 2120)
    cool_time = schedule.get('timing', {}).get('coolingStationCoolTimeSec', 60)

    return wafers, op_map, {'pc': pc_time, 'epi': epi_time, 'cool': cool_time}

def wafer_history_html(wafer_id, wafers, gantt, op_map, recipe_times, device, sim_time):
    entries = wafers.get(wafer_id, [])
    if not entries: return ''

    import math
    def _op(op_key):
        op = op_map.get(op_key, {})
        p = op.get('pickTimeSec', 0); r = op.get('rotateTimeSec', 0); pl = op.get('placeTimeSec', 0)
        cp = math.ceil(p); cr = math.ceil(r); cpl = math.ceil(pl)
        return cp, cr, cpl, cp + cr + cpl

    rows = ''
    prev_end = 0
    first_start = entries[0]['startTimeSec'] if entries else 0

    # Step 0: ATM: FOUP → Aligner
    p, r, pl, xfer = _op('foupToAligner')
    rows += f'<tr><td>{format_time(prev_end)}</td><td>{format_time(xfer)}</td><td>-</td><td>ATM1: FOUP→Aligner</td><td>-</td><td>-</td><td>ATM1</td><td>{p}s+{r}s+{pl}s</td><td>{xfer}s</td></tr>'
    prev_end += xfer

    # Step 1: Aligner
    align_time = int(device.get('efem', {}).get('aligner', {}).get('alignTimeSec', 4.4))
    rows += f'<tr><td>{format_time(prev_end)}</td><td>{format_time(align_time)}</td><td>ALIGNER</td><td>Aligner</td><td>{format_time(align_time)}</td><td>-</td><td>-</td><td>-</td><td>-</td></tr>'
    prev_end += align_time

    # Step 2: ATM: Aligner → LL
    p, r, pl, xfer = _op('alignerToLL')
    rows += f'<tr><td>{format_time(prev_end)}</td><td>{format_time(xfer)}</td><td>-</td><td>ATM1: Aligner→LL</td><td>-</td><td>-</td><td>ATM1</td><td>{p}s+{r}s+{pl}s</td><td>{xfer}s</td></tr>'
    prev_end += xfer

    # Step 3: TM1: LL → PreClean
    p, r, pl, xfer = _op('LL_TO_PRECLEAN')
    pc_time = recipe_times['pc']
    # Find actual PC entry
    pc_entry = next((e for e in entries if e['type'] == 'PRECLEAN'), None)
    if pc_entry:
        pc_start = pc_entry['startTimeSec']
        # Robot gap from prev to PC entry
        if pc_start > prev_end:
            rows += f'<tr><td>{format_time(prev_end)}</td><td>{format_time(pc_start - prev_end)}</td><td>-</td><td>TM1: LL→PC</td><td>-</td><td>-</td><td>TM1</td><td>{p}s+{r}s+{pl}s</td><td>{xfer}s</td></tr>'
        prev_end = pc_start if pc_start > prev_end else prev_end

    for e in entries:
        start = e['startTimeSec']
        end = e['endTimeSec'] if e.get('endTimeSec', 0) > 0 else sim_time
        loc = e['location']
        etype = e['type']
        chamber_total = max(0, end - start)

        # Determine process time and outgoing op
        proc_time = 0
        dwell_time = 0
        op_title = ''
        pick_t = rot_t = place_t = xfer_total = 0

        if etype == 'PRECLEAN':
            proc_time = pc_time
            p, r, pl, xfer_total = _op('PRECLEAN_TO_PT')
            op_title = 'TM1: PC→PT'
        elif etype == 'EPI':
            proc_time = recipe_times['epi']
            p, r, pl, xfer_total = _op('EPI_TO_PT')
            op_title = 'TM2: EPI→PT'
        elif etype == 'PASSTHROUGH':
            p, r, pl, xfer_total = _op('PT_TO_EPI')
            op_title = 'TM2: PT→EPI'
        elif etype == 'PT_RETURN':
            is_cool = 'S0' in loc or 'S1' in loc
            proc_time = recipe_times['cool'] if is_cool else 0
            p, r, pl, xfer_total = _op('PT_TO_LL')
            op_title = 'TM1: PT→LL'

        dwell_time = max(0, chamber_total - proc_time - xfer_total)
        t_str = format_time(start)
        rows += f'<tr><td>{t_str}</td><td>{format_time(chamber_total)}</td><td>{loc}</td><td>{etype}</td><td>{format_time(proc_time)}</td><td>{format_time(dwell_time)}</td><td>{op_title}</td><td>{p}s+{r}s+{pl}s</td><td>{xfer_total}s</td></tr>'
        prev_end = end

    return rows

def robot_timing_rows(device):
    rows = ''
    for tm in device.get('transferModules', []):
        for rob in tm.get('robots', []):
            for op_key, op in (rob.get('operations') or {}).items():
                p = op.get('pickTimeSec', 0); r = op.get('rotateTimeSec', 0); pl = op.get('placeTimeSec', 0)
                rows += f'<tr><td>{rob["id"]} {op_key}</td><td>pick {p}s + rot {r}s + place {pl}s = {p+r+pl}s</td><td>-</td></tr>'
    atm = device.get('efem', {}).get('atmRobot', {})
    for op_key in ['foupToAligner', 'alignerToLL']:
        op = atm.get(op_key, {})
        if op:
            p = op.get('pickTimeSec', 0); r = op.get('rotateTimeSec', 0); pl = op.get('placeTimeSec', 0)
            rows += f'<tr><td>ATM1 {op_key}</td><td>pick {p}s + rot {r}s + place {pl}s = {p+r+pl}s</td><td>-</td></tr>'
    return rows

def main():
    state = fetch("/simulation/state")
    gantt = fetch("/simulation/gantt")
    events = fetch("/simulation/events")
    device = fetch("/config/device")
    schedule = fetch("/config/schedule")
    try: am_config = fetch("/config/am")
    except: am_config = None
    try: replay_data = fetch("/simulation/replay")
    except: replay_data = []

    # Robot transfer times from config (only rotate+place subtracted; pick happens in-chamber)
    robots_xfer = {}
    for tm in device.get('transferModules', []):
        for rob in tm.get('robots', []):
            r_id = rob['id']
            def_xfer = rob.get('rotateTimeSec', 0) + rob.get('placeTimeSec', 0)
            ops = {}
            import math
            for k, v in (rob.get('operations') or {}).items():
                pt = math.ceil(v.get('pickTimeSec', 0))
                rt = math.ceil(v.get('rotateTimeSec', 0))
                plt = math.ceil(v.get('placeTimeSec', 0))
                ops[k] = rt + plt  # xfer = rotate + place (pick in-chamber)
                ops[k + '_total'] = pt + rt + plt  # total = full robot op
            robots_xfer[r_id] = {'default': def_xfer, 'ops': ops}

    entry_xfer_map = {
        'PRECLEAN': ('Robot1', 'PRECLEAN_TO_PT', 'xfer'),
        'PASSTHROUGH': ('Robot2', 'PT_TO_EPI', 'total'),
        'PT_RETURN': ('Robot1', 'PT_TO_LL', 'xfer'),
        'EPI': ('Robot2', 'EPI_TO_PT', 'xfer'),
    }

    def get_entry_xfer(etype):
        pair = entry_xfer_map.get(etype, ('Robot1', None, 'xfer'))
        rd = robots_xfer.get(pair[0], {'default': 9, 'ops': {}})
        op_key = pair[1]
        field = op_key + '_total' if pair[2] == 'total' else op_key
        if op_key and field in rd.get('ops', {}):
            return rd['ops'][field]
        return rd.get('default', 9)

    def get_total_xfer(etype):
        """Always return total robot operation time (pick+rotate+place)."""
        pair = entry_xfer_map.get(etype, ('Robot1', None, 'xfer'))
        rd = robots_xfer.get(pair[0], {'default': 9, 'ops': {}})
        op_key = pair[1]
        field = op_key + '_total' if op_key else None
        if field and field in rd.get('ops', {}):
            return rd['ops'][field]
        return rd.get('default', 9)

    total_wafers = state['totalWafers']
    completed = state['completedWafers']
    sim_time = state['currentTimeSec']
    sim_hours = sim_time / 3600.0
    wph = round(completed / sim_hours, 1) if sim_hours > 0 else 0

    # Wafer history data
    wafers_hist, op_map_hist, recipe_times = wafer_history_data(events, gantt, device, schedule)
    # Build wafer history JS data
    sorted_wafer_ids = sorted(wafers_hist.keys(), key=wafer_sort_key)
    wh_js_parts = []
    for wid in sorted_wafer_ids:
        rows = wafer_history_html(wid, wafers_hist, gantt, op_map_hist, recipe_times, device, sim_time)
        if not rows:
            wh_js_parts.append(f'"{wid}": "<p>无数据</p>",')
            continue
        header = '<tr style="background:#0f3460"><th>时间</th><th>腔室耗时</th><th>腔室</th><th>类型</th><th>处理</th><th>驻留</th><th>传出操作</th><th>Pick+Rot+Place</th><th>传输总</th></tr>'
        table = '<div class="mx"><table>' + header + rows + '</table></div>'
        wh_js_parts.append(f'"{wid}": `{table}`,')
    wafer_history_js = '\n'.join(wh_js_parts)
    wafer_history_options_html = '\n'.join(f'<option value="{w}">{w}</option>' for w in sorted_wafer_ids)
    wafer_history_script = f'''<script>
const waferData = {{
{wafer_history_js}
}};

function showWaferHistory() {{
  const wid = document.getElementById('waferSelect').value;
  const div = document.getElementById('waferHistoryTable');
  if (!wid || !waferData[wid]) {{ div.innerHTML = '<p style=color:#888>选择上方 Wafer 查看完整调度历史</p>'; return; }}
  div.innerHTML = waferData[wid];
}}
</script>'''

    # PT slot colors based on device config (cooling=blue, buffer=yellow)
    slot_colors, cooling_slots = get_pt_slot_colors(device)

    # Build wafer-station matrix from gantt entries
    # Filter to actual wafer entries (not BATCH)
    wafer_entries = [e for e in gantt if e['waferId'] not in ('BATCH', 'CLEAN') and not e['waferId'].startswith('BATCH') and not e['waferId'].startswith('CLEAN_')]
    batch_entries = [e for e in gantt if e['waferId'] in ('BATCH',) or e['waferId'].startswith('BATCH')]
    clean_entries = [e for e in gantt if e.get('type') == 'CLEAN']

    # Matrix: wafer -> {location: {fwd: time, fwd_proc: time, fwd_dwell: time, ret: ...}}
    wafer_matrix = {}
    recipes = schedule.get('recipes', {})
    cool_time = schedule.get('timing', {}).get('coolingStationCoolTimeSec', 60)
    pc_recipe = recipes.get('PRECLEAN', {}).get('avgProcessTimeSec', 280)
    epi_recipe = recipes.get('EPI', {}).get('avgProcessTimeSec', 2120)

    for w in sorted(wafer_entries, key=lambda e: (e['waferId'], e['startTimeSec'])):
        wid = w['waferId']
        loc = w['location']
        end = w['endTimeSec'] if w['endTimeSec'] > 0 else sim_time
        etype = w['type']
        xfer = get_entry_xfer(etype)
        dur = max(0, end - w['startTimeSec'] - xfer)

        # Compute process and dwell
        proc, dwell = 0, dur
        if etype == 'PRECLEAN':
            proc, dwell = pc_recipe, max(0, dur - pc_recipe)
        elif etype == 'EPI':
            proc, dwell = epi_recipe, max(0, dur - epi_recipe)
        elif etype == 'PT_RETURN':
            proc, dwell = cool_time, max(0, dur - cool_time)
        elif etype == 'PASSTHROUGH':
            proc, dwell = 0, dur

        if wid not in wafer_matrix:
            wafer_matrix[wid] = {}
        if loc not in wafer_matrix[wid]:
            wafer_matrix[wid][loc] = {'fwd': 0, 'fwd_proc': 0, 'fwd_dwell': 0,
                                       'ret': 0, 'ret_proc': 0, 'ret_dwell': 0}
        if etype == 'PT_RETURN':
            wafer_matrix[wid][loc]['ret'] += dur
            wafer_matrix[wid][loc]['ret_proc'] += proc
            wafer_matrix[wid][loc]['ret_dwell'] += dwell
        elif etype == 'PASSTHROUGH':
            wafer_matrix[wid][loc]['fwd'] += dur
            wafer_matrix[wid][loc]['fwd_proc'] += proc
            wafer_matrix[wid][loc]['fwd_dwell'] += dwell
        else:
            wafer_matrix[wid][loc]['fwd'] += dur
            wafer_matrix[wid][loc]['fwd_proc'] += proc
            wafer_matrix[wid][loc]['fwd_dwell'] += dwell

    preclean_cols = ['PreClean1', 'PreClean2']
    pt_cols = ['PT1_S0', 'PT1_S1', 'PT2_S0', 'PT2_S1']
    epi_cols = ['EPI1', 'EPI2', 'EPI3', 'EPI4']

    # Per-slot utilization (dwell-based)
    slot_util = {}
    for ch in pt_cols:
        occ = 0
        for e in wafer_entries:
            if e['location'] == ch:
                end = e['endTimeSec'] if e['endTimeSec'] > 0 else sim_time
                xfer = get_entry_xfer(e["type"])
                occ += max(0, end - e['startTimeSec'] - xfer)
        slot_util[ch] = min(100, round(occ * 100 / max(sim_time, 1)))

    # Per-type chamber stats (dwell-based)
    chamber_times = {}
    for entry in wafer_entries:
        etype = entry['type']
        end = entry['endTimeSec'] if entry['endTimeSec'] > 0 else sim_time

        xfer = get_entry_xfer(e["type"])
        dur = max(0, end - entry['startTimeSec'] - xfer)
        if etype not in chamber_times:
            chamber_times[etype] = {'total': 0, 'count': 0}
        chamber_times[etype]['total'] += dur
        chamber_times[etype]['count'] += 1

    # Wafer cycle stats
    wafer_cycle_stats = {}
    for entry in wafer_entries:
        wid = entry['waferId']
        start = entry['startTimeSec']
        end = entry['endTimeSec'] if entry['endTimeSec'] > 0 else sim_time
        if wid not in wafer_cycle_stats:
            wafer_cycle_stats[wid] = {'start': start, 'end': end}
        wafer_cycle_stats[wid]['end'] = max(wafer_cycle_stats[wid]['end'], end)
        wafer_cycle_stats[wid]['start'] = min(wafer_cycle_stats[wid]['start'], start)

    total_cycle = sum(ws['end'] - ws['start'] for ws in wafer_cycle_stats.values())
    avg_cycle = int(total_cycle / max(len(wafer_cycle_stats), 1))

    # Build matrix HTML: PreClean | PT_fwd × 4 | EPI | PT_ret × 4 | Total
    sorted_wafers = sorted(wafer_matrix.keys(), key=wafer_sort_key)
    matrix_html = '<tr><th>Wafer</th>'
    matrix_html += '<th>PreClean</th>'
    for c in pt_cols:
        cs = "❄" if c in cooling_slots else ""
        matrix_html += f'<th>{c}<br>fwd{cs}</th>'
    matrix_html += '<th>EPI</th>'
    for c in pt_cols:
        cs = "❄" if c in cooling_slots else ""
        matrix_html += f'<th>{c}<br>ret{cs}</th>'
    matrix_html += '<th>Total</th></tr>'

    for wid in sorted_wafers:
        wd = wafer_matrix.get(wid, {})
        matrix_html += f'<tr><td>{wid}</td>'
        total = 0
        # PreClean
        pc_time = sum(wd.get(c, {}).get('fwd', 0) for c in preclean_cols)
        pc_proc = sum(wd.get(c, {}).get('fwd_proc', 0) for c in preclean_cols)
        pc_dwell = sum(wd.get(c, {}).get('fwd_dwell', 0) for c in preclean_cols)
        total += pc_time
        matrix_html += f'<td>{format_short(pc_time)}<br><small>P:{format_short(pc_proc)} D:{format_short(pc_dwell)}</small></td>'
        # PT fwd
        for c in pt_cols:
            t = wd.get(c, {}).get('fwd', 0)
            p = wd.get(c, {}).get('fwd_proc', 0)
            d = wd.get(c, {}).get('fwd_dwell', 0)
            total += t
            highlight = ' style="font-weight:bold;color:#FF9800"' if t > 300 else ''
            sub = f'<br><small>P:{format_short(p)} D:{format_short(d)}</small>' if t >= 0 and (p > 0 or d > 0 or t > 0) else ''
            if t > 0 or (t == 0 and wd.get(c, {}).get('fwd', -1) >= 0):
                matrix_html += f'<td{highlight}>{format_short(t)}{sub}</td>'
            else:
                matrix_html += '<td>-</td>'
        # EPI
        epi_time = sum(wd.get(c, {}).get('fwd', 0) for c in epi_cols)
        epi_proc = sum(wd.get(c, {}).get('fwd_proc', 0) for c in epi_cols)
        epi_dwell = sum(wd.get(c, {}).get('fwd_dwell', 0) for c in epi_cols)
        total += epi_time
        matrix_html += f'<td>{format_short(epi_time)}<br><small>P:{format_short(epi_proc)} D:{format_short(epi_dwell)}</small></td>'
        # PT ret
        for c in pt_cols:
            t = wd.get(c, {}).get('ret', 0)
            p = wd.get(c, {}).get('ret_proc', 0)
            d = wd.get(c, {}).get('ret_dwell', 0)
            total += t
            sub = f'<br><small>P:{format_short(p)} D:{format_short(d)}</small>' if t >= 0 and (p > 0 or d > 0 or t > 0) else ''
            if t > 0 or (t == 0 and wd.get(c, {}).get('ret', -1) >= 0):
                matrix_html += f'<td>{format_short(t)}{sub}</td>'
            else:
                matrix_html += '<td>-</td>'
        matrix_html += f'<td style="font-weight:bold;color:#00d4ff">{format_short(total)}</td></tr>'

    # Config rows
    config_rows = ""
    for ctype, recipe in schedule.get('recipes', {}).items():
        config_rows += f"<tr><td>{ctype}</td><td>{recipe.get('avgProcessTimeSec','-')}s</td><td>{recipe.get('maxDwellTimeSec','-')}s</td></tr>"
    timing = schedule.get('timing', {})

    # Chamber usage table (total time, count, avg)
    usage_rows = ""
    for ctype in ['PRECLEAN', 'EPI']:
        if ctype in chamber_times:
            ct = chamber_times[ctype]
            avg = ct['total'] // max(ct['count'], 1)
            usage_rows += f"<tr><td>{ctype}</td><td>{format_time(ct['total'])}</td><td>{ct['count']}</td><td>{format_time(avg)}</td></tr>"
    for ch in pt_cols:
        cs = "❄" if ch in cooling_slots else ""
        tot = sum(max(0, (e['endTimeSec'] if e['endTimeSec'] > 0 else sim_time) - e['startTimeSec']
                        - get_entry_xfer(e['type']))
                  for e in wafer_entries if e['location'] == ch)
        cnt = len([e for e in wafer_entries if e['location'] == ch])
        avg = tot // max(cnt, 1) if cnt > 0 else 0
        usage_rows += f"<tr><td>{ch} {cs}</td><td>{format_time(tot)}</td><td>{cnt}</td><td>{format_time(avg)}</td></tr>"

    # Legend
    legend = ""
    for loc, sc in slot_colors.items():
        cs = "❄" if loc in cooling_slots else ""
        legend += f'<div class="li"><div class="lc" style="background:{sc["fwd"]}"></div>{loc}fwd{cs}</div>'
        legend += f'<div class="li"><div class="lc" style="background:{sc["ret"]}"></div>{loc}ret{cs}</div>'
    legend += f'<div class="li"><div class="lc" style="background:#FF9800"></div>PreClean</div>'
    legend += f'<div class="li"><div class="lc" style="background:#4CAF50"></div>EPI</div>'
    legend += f'<div class="li"><div class="lc" style="background:#FF5722"></div>1X Clean</div>'
    legend += f'<div class="li"><div class="lc" style="background:#2196F3"></div>LL</div>'
    legend += f'<div class="li"><div class="lc" style="background:#9C27B0"></div>LL Ret</div>'

    # Gantt chart
    max_time = max(sim_time, 1)
    scale = 100.0 / max_time
    chamber_order = ['LL1', 'LL2', 'ALIGNER', 'PreClean1', 'PreClean2',
                     'PT1_S0', 'PT1_S1', 'PT2_S0', 'PT2_S1',
                     'EPI1', 'EPI2', 'EPI3', 'EPI4']
    chamber_wafer_entries = {ch: [] for ch in chamber_order}
    for e in wafer_entries:
        loc = e['location']
        if loc in chamber_wafer_entries:
            chamber_wafer_entries[loc].append(e)
    for e in clean_entries:
        loc = e['location']
        if loc in chamber_wafer_entries:
            chamber_wafer_entries[loc].append(e)

    rows_html = ""
    for ch in chamber_order:
        entries = chamber_wafer_entries.get(ch, [])
        for be in batch_entries:
            if be['location'] == ch:
                entries.append(be)
        occ = sum(max(0, (e['endTimeSec'] if e['endTimeSec'] > 0 else sim_time) - e['startTimeSec'] - get_entry_xfer(e['type']))
                  for e in entries)
        if ch.startswith('EPI') and entries:
            # EPI utilization = process time / active window (first in → last out)
            first_in = min(e['startTimeSec'] for e in entries)
            last_out = max((e['endTimeSec'] if e['endTimeSec'] > 0 else sim_time) for e in entries)
            denominator = last_out - first_in
            util = min(100, round(occ * 100 / denominator)) if denominator > 0 else 0
        else:
            util = min(100, round(occ * 100 / max_time))
        bars_html = ""
        for e in entries:
            start = e['startTimeSec']
            end = e['endTimeSec'] if e['endTimeSec'] > 0 else sim_time
            etype = e.get('type', '')
            if etype == 'CLEAN':
                dur = max(0, end - start)  # clean has no outgoing xfer
            else:
                dur = max(0, end - start - get_entry_xfer(etype))  # actual dwell for visual bar
            left_pct = start * scale
            width_pct = max(dur * scale, 0.5)
            if etype == 'CLEAN':
                color = '#FF5722'
                label = '🧹'
                ttip = f"CLEAN {e['location']} {format_time(dur)}"
            else:
                color = gantt_color(e, slot_colors)
                label = e.get('waferId', '')[:8]
                ttip = f"{e['waferId']} {e['type']} dwell {format_time(dur)}"
            bars_html += f'<div class="gb" style="left:{left_pct:.2f}%;width:{width_pct:.2f}%;background:{color};" title="{ttip}">{label}</div>'
        rows_html += f'<div class="gr"><div class="gl">{ch} <span class="gu">{util}%</span></div><div class="ga">{bars_html}</div></div>'

    num_ticks = 10
    tick_interval = max_time / num_ticks
    ruler = ""
    for i in range(num_ticks + 1):
        ruler += f'<span>{format_time(int(i * tick_interval))}</span>'

    html = f'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>EPI Cluster Tool 模拟报告</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{font-family:Segoe UI,Microsoft YaHei,sans-serif;background:#1a1a2e;color:#e0e0e0;padding:20px}}
h1{{text-align:center;color:#00d4ff;margin-bottom:8px;font-size:26px}}
h2{{color:#00d4ff;border-bottom:2px solid #333;padding-bottom:6px;margin:22px 0 12px;font-size:18px}}
.subtitle{{text-align:center;color:#888;margin-bottom:24px;font-size:13px}}
.sg{{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:14px;margin-bottom:24px}}
.sc{{background:#16213e;border:1px solid #333;border-radius:10px;padding:18px;text-align:center}}
.sc .v{{font-size:32px;font-weight:bold;color:#00d4ff}}
.sc .l{{font-size:12px;color:#999;margin-top:4px}}
.sc.g .v{{color:#4CAF50}}
.sc.o .v{{color:#FF9800}}
.sc.p .v{{color:#E91E63}}
table{{width:100%;border-collapse:collapse;margin-bottom:18px;background:#16213e;border-radius:8px;overflow:hidden}}
th,td{{padding:6px 10px;text-align:left;border-bottom:1px solid #2a2a4a;font-size:12px;white-space:nowrap}}
th{{background:#0f3460;color:#00d4ff;font-weight:600;position:sticky;top:0;z-index:1}}
.mx{{overflow-x:auto;max-height:500px;overflow-y:auto;background:#16213e;border-radius:10px;border:1px solid #333;margin-bottom:18px}}
.mx table{{margin-bottom:0}}
.mx th:first-child,.mx td:first-child{{position:sticky;left:0;background:#16213e;z-index:2;font-weight:bold}}
.mx tr:nth-child(even) td{{background:#1a2744}}
.mx tr:nth-child(even) td:first-child{{background:#1a2744}}
.gc{{overflow-x:auto;background:#16213e;border-radius:10px;padding:14px;border:1px solid #333}}
.gchart{{position:relative;min-width:1100px}}
.gr{{display:flex;align-items:center;height:26px;margin:1px 0;background:#0d1117;border-radius:4px}}
.gl{{width:120px;min-width:120px;font-size:11px;color:#aaa;padding-left:6px;font-family:monospace;display:flex;justify-content:space-between;padding-right:8px}}
.gu{{color:#00d4ff;font-weight:bold;font-size:10px}}
.ga{{flex:1;position:relative;height:100%}}
.gb{{position:absolute;height:20px;top:3px;border-radius:3px;font-size:9px;color:#111;display:flex;align-items:center;justify-content:center;overflow:hidden;white-space:nowrap;min-width:2px}}
.gtr{{display:flex;font-size:10px;color:#666;margin-left:120px}}
.gtr span{{flex:1;text-align:left;overflow:hidden}}
.legend{{display:flex;gap:14px;flex-wrap:wrap;margin:10px 0 16px}}
.li{{display:flex;align-items:center;gap:5px;font-size:11px}}
.lc{{width:13px;height:13px;border-radius:3px}}
footer{{text-align:center;color:#555;margin-top:36px;font-size:11px}}
/* Replay player styles */
.rp{{background:#16213e;border:1px solid #333;border-radius:10px;padding:14px;margin-bottom:18px}}
.rp-ctrl{{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:10px}}
.rp-ctrl button{{background:#0f3460;color:#00d4ff;border:1px solid #333;padding:6px 14px;border-radius:4px;cursor:pointer;font-size:13px}}
.rp-ctrl button:hover{{background:#1a4a80}}
.rp-ctrl button:disabled{{opacity:0.4;cursor:default}}
.rp-ctrl select,.rp-ctrl input{{background:#16213e;color:#00d4ff;border:1px solid #333;padding:4px 8px;border-radius:4px;font-size:12px}}
.rp-time{{color:#00d4ff;font-size:14px;font-weight:bold;min-width:80px}}
.rp-progress{{flex:1;min-width:150px;height:6px;-webkit-appearance:none;appearance:none;background:#2a2a4a;border-radius:3px;outline:none}}
.rp-progress::-webkit-slider-thumb{{-webkit-appearance:none;width:14px;height:14px;background:#00d4ff;border-radius:50%;cursor:pointer}}
.rp-canvas{{width:100%;max-width:1100px;background:#0d1117;border-radius:8px;display:block;margin:0 auto}}
</style>
</head>
<body>
<h1>EPI Cluster Tool 模拟报告</h1>
<p class="subtitle">设备: {device['equipmentName']} ({device['equipmentId']}) | 模拟时长: {format_time(sim_time)} | 状态: {state['status']}</p>

<h2>核心指标</h2>
<div class="sg">
  <div class="sc g"><div class="v">{completed}/{total_wafers}</div><div class="l">完成 Wafer 数</div></div>
  <div class="sc"><div class="v">{wph}</div><div class="l">WPH (Wafer Per Hour)</div></div>
  <div class="sc o"><div class="v">{format_time(sim_time)}</div><div class="l">总模拟时间</div></div>
  <div class="sc p"><div class="v">{format_time(avg_cycle)}</div><div class="l">平均单 Wafer 周期</div></div>
</div>

<h2>工艺参数</h2>
<table>
<tr><th>项目</th><th>工艺时间</th><th>最大驻留时间</th></tr>
{config_rows}
<tr><td>LL Pump</td><td>{timing.get('loadlockPumpTimeSec','-')}s</td><td>-</td></tr>
<tr><td>LL Vent</td><td>{timing.get('loadlockVentTimeSec','-')}s</td><td>-</td></tr>
<tr><td>LL Load/Unload</td><td>{timing.get('loadlockLoadTimeSec','-')}s/{timing.get('loadlockUnloadTimeSec','-')}s</td><td>-</td></tr>
<tr><td>PT Transfer</td><td>{timing.get('passthroughTransferTimeSec','-')}s</td><td>-</td></tr>
<tr><td>CoolingStation</td><td>{timing.get('coolingStationCoolTimeSec','-')}s</td><td>-</td></tr>
{robot_timing_rows(device)}
</table>

<h2>腔室使用时间</h2>
<table>
<tr><th>腔室</th><th>总使用时间</th><th>使用次数</th><th>平均每次</th></tr>
{usage_rows}
</table>

<h2>约束违反统计</h2>
{violation_table(gantt, schedule, get_total_xfer, am_config)}

<h2>Wafer × 工站 耗时矩阵</h2>
<div class="mx">
<table>
{matrix_html}
</table>
</div>

<h2>完整甘特图</h2>
<div class="legend">{legend}</div>
<div class="gc">
<div class="gchart">
<div class="gtr">{ruler}</div>
{rows_html}
</div>
</div>

<h2>机台动画回放</h2>
<style>
#rp-wrap{{background:#111827;border-radius:8px;padding:12px;overflow:hidden}}
#rp-ctrl{{display:flex;gap:8px;align-items:center;margin-bottom:8px;flex-wrap:wrap}}
#rp-ctrl button{{padding:6px 12px;border:none;border-radius:4px;background:#374151;color:#ddd;cursor:pointer;font-size:12px}}
#rp-ctrl button:hover{{background:#4B5563}}
#rp-ctrl select{{padding:4px 8px;border-radius:4px;background:#1F2937;color:#ddd;border:1px solid #4B5563;font-size:12px}}
#rp-ctrl .rp-time{{font-family:monospace;font-size:14px;color:#00d4ff;min-width:70px}}
#rp-ctrl input[type=range]{{flex:1;min-width:200px;accent-color:#00BCD4}}
#rp-status{{display:flex;gap:16px;margin-bottom:6px;font-size:12px;color:#888}}
#rp-status b{{color:#00d4ff}}
.rp-svg-wrap{{width:100%;overflow-x:auto}}
[id^="arm-"] {{ transition: transform 0.3s linear; }}
[id^="arml-"] {{ transition: x2 0.3s linear; }}
</style>
<div id="rp-wrap">
<div id="rp-ctrl">
  <button id="rp-play" onclick="replayToggle()">▶ 播放</button>
  <button onclick="replayStep(-1)">⏮ -1</button>
  <button onclick="replayStep(1)">⏭ +1</button>
  <span class="rp-time" id="rp-time">00:00:00</span>
  <span style="color:#aaa;font-size:12px">速度</span>
  <select id="rp-speed" onchange="replaySpeed()">
    <option value="0.25">0.25x</option><option value="0.5">0.5x</option>
    <option value="1" selected>1x</option><option value="2">2x</option>
    <option value="5">5x</option><option value="10">10x</option>
    <option value="25">25x</option><option value="50">50x</option>
    <option value="100">100x</option>
  </select>
  <input type="range" id="rp-progress" min="0" max="100" value="0" oninput="replaySeek()">
</div>
<div id="rp-status">
  <span>完成: <b id="rp-done">0</b>/<b id="rp-total">0</b></span>
  <span>状态: <b id="rp-status-text">-</b></span>
</div>
<div class="rp-svg-wrap">
<svg id="rp-svg" viewBox="0 0 1140 520" style="width:100%;max-height:520px;background:#111827;border-radius:0 0 8px 8px">
  <defs>
    <filter id="glow"><feGaussianBlur stdDeviation="2"/><feMerge><feMergeNode in="SourceGraphic"/></feMerge></filter>
  </defs>

  <!-- EFEM (Atmosphere) -->
  <rect x="5" y="30" width="235" height="460" rx="8" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="6,3"/>
  <text x="120" y="22" text-anchor="middle" fill="#888" font-size="10">EFEM (大气环境)</text>

  <!-- FOUPs -->
      <!-- LP1 FOUP -->
      <g transform="translate(15, 60)">
        <rect width="100" height="130" rx="4" fill="#1a2a3a" stroke="#2196F3" stroke-width="1.5"/>
        <text x="50" y="14" text-anchor="middle" fill="#64B5F6" font-size="9">LP1 (FOUP1)</text>
        <rect id="f-LP1-0" x="20" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-1" x="33" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-2" x="46" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-3" x="59" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-4" x="72" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-5" x="20" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-6" x="33" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-7" x="46" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-8" x="59" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-9" x="72" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-10" x="20" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-11" x="33" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-12" x="46" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-13" x="59" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-14" x="72" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-15" x="20" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-16" x="33" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-17" x="46" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-18" x="59" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-19" x="72" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-20" x="20" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-21" x="33" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-22" x="46" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-23" x="59" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP1-24" x="72" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <text x="50" y="124" text-anchor="middle" fill="#555" font-size="7">25 slots</text>
      </g>
      <!-- LP2 FOUP -->
      <g transform="translate(15, 205)">
        <rect width="100" height="130" rx="4" fill="#1a2a3a" stroke="#2196F3" stroke-width="1.5"/>
        <text x="50" y="14" text-anchor="middle" fill="#64B5F6" font-size="9">LP2 (FOUP2)</text>
        <rect id="f-LP2-0" x="20" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-1" x="33" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-2" x="46" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-3" x="59" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-4" x="72" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-5" x="20" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-6" x="33" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-7" x="46" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-8" x="59" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-9" x="72" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-10" x="20" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-11" x="33" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-12" x="46" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-13" x="59" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-14" x="72" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-15" x="20" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-16" x="33" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-17" x="46" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-18" x="59" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-19" x="72" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-20" x="20" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-21" x="33" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-22" x="46" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-23" x="59" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP2-24" x="72" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <text x="50" y="124" text-anchor="middle" fill="#555" font-size="7">25 slots</text>
      </g>
      <!-- LP3 FOUP -->
      <g transform="translate(15, 350)">
        <rect width="100" height="130" rx="4" fill="#1a2a3a" stroke="#2196F3" stroke-width="1.5"/>
        <text x="50" y="14" text-anchor="middle" fill="#64B5F6" font-size="9">LP3 (FOUP3)</text>
        <rect id="f-LP3-0" x="20" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-1" x="33" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-2" x="46" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-3" x="59" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-4" x="72" y="22" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-5" x="20" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-6" x="33" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-7" x="46" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-8" x="59" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-9" x="72" y="35" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-10" x="20" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-11" x="33" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-12" x="46" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-13" x="59" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-14" x="72" y="48" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-15" x="20" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-16" x="33" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-17" x="46" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-18" x="59" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-19" x="72" y="61" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-20" x="20" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-21" x="33" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-22" x="46" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-23" x="59" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <rect id="f-LP3-24" x="72" y="74" width="11" height="11" rx="1" fill="#1a1a2e" stroke="#333" stroke-width="0.5"/>
        <text x="50" y="124" text-anchor="middle" fill="#555" font-size="7">25 slots</text>
      </g>

  <!-- Aligner (inside EFEM) -->
  <g transform="translate(140, 130)">
    <rect id="rc-ALIGNER" width="55" height="30" rx="4" fill="#2a3a4a" stroke="#FF9800" stroke-width="1.5"/>
    <text x="27" y="12" text-anchor="middle" fill="#FFB74D" font-size="7">Aligner</text>
    <text id="wf-ALIGNER" x="27" y="24" text-anchor="middle" fill="#fff" font-size="7">空</text>
  </g>

  <!-- ATM Robot (inside EFEM) -->
  <g transform="translate(155, 230)">
    <circle r="25" fill="#1a2a3a" stroke="#FF9800" stroke-width="2"/>
    <text y="-10" text-anchor="middle" fill="#FF9800" font-size="8" font-weight="bold">ATM Robot</text>
    <text id="rbst-ATM1" y="1" text-anchor="middle" fill="#aaa" font-size="6">空闲</text>
    <g id="arm-ATM1" style="transform:rotate(0deg);transform-origin:0px 0px">
      <line id="arml-ATM1" x1="0" y1="0" x2="20" y2="0" stroke="#FF9800" stroke-width="3" stroke-linecap="round"/>
      <circle id="armc-ATM1" r="5" cx="20" cy="0" fill="#FF9800" stroke="#fff" stroke-width="1" filter="url(#glow)"/>
      <text id="armw-ATM1" x="20" y="12" text-anchor="middle" fill="#FFD54F" font-size="7" style="display:none"></text>
    </g>
  </g>

  <!-- Connections: ATM to LL -->
  <g stroke="#555" stroke-width="1" fill="none" stroke-dasharray="4,3">
    <line x1="180" y1="220" x2="245" y2="195"/>
    <line x1="180" y1="240" x2="245" y2="305"/>
  </g>

  <!-- BatchLoadLocks -->
  <g transform="translate(250, 160)">
    <rect id="rc-LL1" width="80" height="55" rx="6" fill="#2a3a4a" stroke="#00BCD4" stroke-width="1.5"/>
    <text x="40" y="14" text-anchor="middle" fill="#aaa" font-size="8">LL1 (BLL)</text>
    <text id="st-LL1" x="40" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wc-LL1" x="40" y="44" text-anchor="middle" fill="#FFD54F" font-size="9">0片</text>
    <rect id="pb-LL1" x="5" y="50" width="0" height="3" rx="1" fill="#00BCD4"/>
  </g>
  <g transform="translate(250, 275)">
    <rect id="rc-LL2" width="80" height="55" rx="6" fill="#2a3a4a" stroke="#00BCD4" stroke-width="1.5"/>
    <text x="40" y="14" text-anchor="middle" fill="#aaa" font-size="8">LL2 (BLL)</text>
    <text id="st-LL2" x="40" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wc-LL2" x="40" y="44" text-anchor="middle" fill="#FFD54F" font-size="9">0片</text>
    <rect id="pb-LL2" x="5" y="50" width="0" height="3" rx="1" fill="#00BCD4"/>
  </g>

  <!-- Connections: LL to TM1 -->
  <g stroke="#334" stroke-width="1.5" fill="none">
    <line x1="330" y1="185" x2="360" y2="215"/>
    <line x1="330" y1="305" x2="360" y2="245"/>
  </g>

  <!-- Vacuum boundary -->
  <rect x="345" y="35" width="10" height="450" rx="2" fill="#0f3460" stroke="#00BCD4" stroke-width="1"/>
  <text x="350" y="25" text-anchor="middle" fill="#00BCD4" font-size="8">真空</text>

  <!-- PreClean Chambers -->
  <g transform="translate(335, 50)">
    <rect id="rc-PreClean1" width="90" height="45" rx="6" fill="#2a3a4a" stroke="#FF9800" stroke-width="1.5"/>
    <text x="45" y="14" text-anchor="middle" fill="#FFB74D" font-size="9">PreClean1</text>
    <text id="st-PreClean1" x="45" y="27" text-anchor="middle" fill="#fff" font-size="10">空闲</text>
    <text id="wf-PreClean1" x="45" y="40" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-PreClean1" x="5" y="42" width="0" height="2" rx="1" fill="#FF9800"/>
  </g>
  <g transform="translate(335, 365)">
    <rect id="rc-PreClean2" width="90" height="45" rx="6" fill="#2a3a4a" stroke="#FF9800" stroke-width="1.5"/>
    <text x="45" y="14" text-anchor="middle" fill="#FFB74D" font-size="9">PreClean2</text>
    <text id="st-PreClean2" x="45" y="27" text-anchor="middle" fill="#fff" font-size="10">空闲</text>
    <text id="wf-PreClean2" x="45" y="40" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-PreClean2" x="5" y="42" width="0" height="2" rx="1" fill="#FF9800"/>
  </g>

  <!-- TM1 -->
  <g transform="translate(380, 230)">
    <circle r="35" fill="#1a2a3a" stroke="#00BCD4" stroke-width="2"/>
    <text y="-12" text-anchor="middle" fill="#00BCD4" font-size="9" font-weight="bold">TM1</text>
    <text id="rbst-TM1" y="2" text-anchor="middle" fill="#aaa" font-size="7">空闲</text>
    <g id="arm-TM1" style="transform:rotate(0deg);transform-origin:0px 0px">
      <line id="arml-TM1" x1="0" y1="0" x2="30" y2="0" stroke="#FF5722" stroke-width="3" stroke-linecap="round"/>
      <circle id="armc-TM1" r="6" cx="30" cy="0" fill="#FF5722" stroke="#fff" stroke-width="1" filter="url(#glow)"/>
      <text id="armw-TM1" x="30" y="12" text-anchor="middle" fill="#FFD54F" font-size="7" style="display:none"></text>
    </g>
  </g>

  <!-- PassThroughs -->
  <g transform="translate(485, 170)">
    <rect id="rc-PT1_S0" width="65" height="34" rx="4" fill="#2a3a4a" stroke="#FFEB3B" stroke-width="1.5"/>
    <text id="ptname-PT1_S0" x="32" y="12" text-anchor="middle" fill="#FFF176" font-size="8">PT1_S0</text>
    <text id="wf-PT1_S0" x="32" y="26" text-anchor="middle" fill="#fff" font-size="9">空</text>
    <text id="cool-PT1_S0" x="32" y="34" text-anchor="middle" fill="#00BCD4" font-size="7" style="display:none">❄</text>
  </g>
  <g transform="translate(485, 212)">
    <rect id="rc-PT1_S1" width="65" height="34" rx="4" fill="#2a3a4a" stroke="#FFEB3B" stroke-width="1.5"/>
    <text id="ptname-PT1_S1" x="32" y="12" text-anchor="middle" fill="#FFF176" font-size="8">PT1_S1</text>
    <text id="wf-PT1_S1" x="32" y="26" text-anchor="middle" fill="#fff" font-size="9">空</text>
    <text id="cool-PT1_S1" x="32" y="34" text-anchor="middle" fill="#00BCD4" font-size="7" style="display:none">❄</text>
  </g>
  <g transform="translate(485, 245)">
    <rect id="rc-PT2_S0" width="65" height="34" rx="4" fill="#2a3a4a" stroke="#FFEB3B" stroke-width="1.5"/>
    <text id="ptname-PT2_S0" x="32" y="12" text-anchor="middle" fill="#FFF176" font-size="8">PT2_S0</text>
    <text id="wf-PT2_S0" x="32" y="26" text-anchor="middle" fill="#fff" font-size="9">空</text>
    <text id="cool-PT2_S0" x="32" y="34" text-anchor="middle" fill="#00BCD4" font-size="7" style="display:none">❄</text>
  </g>
  <g transform="translate(485, 287)">
    <rect id="rc-PT2_S1" width="65" height="34" rx="4" fill="#2a3a4a" stroke="#FFEB3B" stroke-width="1.5"/>
    <text id="ptname-PT2_S1" x="32" y="12" text-anchor="middle" fill="#FFF176" font-size="8">PT2_S1</text>
    <text id="wf-PT2_S1" x="32" y="26" text-anchor="middle" fill="#fff" font-size="9">空</text>
    <text id="cool-PT2_S1" x="32" y="34" text-anchor="middle" fill="#00BCD4" font-size="7" style="display:none">❄</text>
  </g>

  <!-- TM2 -->
  <g transform="translate(620, 230)">
    <circle r="42" fill="#1a2a3a" stroke="#E91E63" stroke-width="2"/>
    <text y="-16" text-anchor="middle" fill="#E91E63" font-size="10" font-weight="bold">TM2</text>
    <text id="rbst-TM2" y="-2" text-anchor="middle" fill="#aaa" font-size="8">空闲</text>
    <g id="arm-TM2" style="transform:rotate(0deg);transform-origin:0px 0px">
      <line id="arml-TM2" x1="0" y1="0" x2="30" y2="0" stroke="#E91E63" stroke-width="3" stroke-linecap="round"/>
      <circle id="armc-TM2" r="6" cx="30" cy="0" fill="#E91E63" stroke="#fff" stroke-width="1" filter="url(#glow)"/>
      <text id="armw-TM2" x="30" y="12" text-anchor="middle" fill="#FFD54F" font-size="7" style="display:none"></text>
    </g>
  </g>

  <!-- EPI Chambers -->
  <g transform="translate(710, 40)">
    <rect id="rc-EPI1" width="95" height="50" rx="6" fill="#2a3a4a" stroke="#4CAF50" stroke-width="1.5"/>
    <text x="47" y="14" text-anchor="middle" fill="#81C784" font-size="9">EPI1</text>
    <text id="st-EPI1" x="47" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wf-EPI1" x="47" y="42" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-EPI1" x="5" y="46" width="0" height="3" rx="1" fill="#4CAF50"/>
  </g>
  <g transform="translate(710, 135)">
    <rect id="rc-EPI2" width="95" height="50" rx="6" fill="#2a3a4a" stroke="#4CAF50" stroke-width="1.5"/>
    <text x="47" y="14" text-anchor="middle" fill="#81C784" font-size="9">EPI2</text>
    <text id="st-EPI2" x="47" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wf-EPI2" x="47" y="42" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-EPI2" x="5" y="46" width="0" height="3" rx="1" fill="#4CAF50"/>
  </g>
  <g transform="translate(710, 230)">
    <rect id="rc-EPI3" width="95" height="50" rx="6" fill="#2a3a4a" stroke="#4CAF50" stroke-width="1.5"/>
    <text x="47" y="14" text-anchor="middle" fill="#81C784" font-size="9">EPI3</text>
    <text id="st-EPI3" x="47" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wf-EPI3" x="47" y="42" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-EPI3" x="5" y="46" width="0" height="3" rx="1" fill="#4CAF50"/>
  </g>
  <g transform="translate(710, 325)">
    <rect id="rc-EPI4" width="95" height="50" rx="6" fill="#2a3a4a" stroke="#4CAF50" stroke-width="1.5"/>
    <text x="47" y="14" text-anchor="middle" fill="#81C784" font-size="9">EPI4</text>
    <text id="st-EPI4" x="47" y="28" text-anchor="middle" fill="#fff" font-size="10" font-weight="bold">空闲</text>
    <text id="wf-EPI4" x="47" y="42" text-anchor="middle" fill="#FFD54F" font-size="9" style="display:none"></text>
    <rect id="pb-EPI4" x="5" y="46" width="0" height="3" rx="1" fill="#4CAF50"/>
  </g>

  <!-- Legend -->
  <g transform="translate(820, 435)">
    <text x="0" y="10" fill="#888" font-size="9">FOUP: </text>
    <rect x="42" y="2" width="10" height="10" rx="1" fill="#555"/>
    <text x="56" y="11" fill="#888" font-size="8">未处理</text>
    <rect x="100" y="2" width="10" height="10" rx="1" fill="#1a1a2e" stroke="#333"/>
    <text x="114" y="11" fill="#888" font-size="8">已取走</text>
    <rect x="158" y="2" width="10" height="10" rx="1" fill="#4CAF50"/>
    <text x="172" y="11" fill="#888" font-size="8">已完成</text>
  </g>
</svg>
</div>
</div>
<script>
const REPLAY = {json.dumps(replay_data)};
const COOLING_SLOTS = new Set({json.dumps(list(cooling_slots))});

const STATE_COLORS = {{
  IDLE:'#2a3a4a', PROCESSING:'#1b5e20', DONE:'#e65100',
  PUMPING:'#0d47a1', VENTING:'#4a148c', READY:'#006064',
  LOADING:'#3e2723', UNLOADING:'#3e2723',
  CLEANING:'#FF5722', PURGING:'#9C27B0', COOLING:'#0288D1'
}};
const STATE_LABELS = {{
  IDLE:'空闲', PROCESSING:'处理中', DONE:'完成', PUMPING:'抽真空',
  VENTING:'充气', READY:'就绪', LOADING:'装载', UNLOADING:'卸载',
  CLEANING:'清洗', PURGING:'吹扫', COOLING:'冷却'
}};

const CHAMBER_IDS = ['LL1','LL2','PreClean1','PreClean2','PT1_S0','PT1_S1','PT2_S0','PT2_S1','EPI1','EPI2','EPI3','EPI4'];
const CHAMBER_SET = new Set(CHAMBER_IDS);
const STATE_ID_IDS = new Set(['LL1','LL2','PreClean1','PreClean2','EPI1','EPI2','EPI3','EPI4']);
const PROGRESS_IDS = new Set(['LL1','LL2','PreClean1','PreClean2','EPI1','EPI2','EPI3','EPI4']);
const FOUP_NAMES = ['LP1','LP2','LP3'];

const atmAngles = {{ LP1:-131, LP2:156, LP3:116, ALIGNER:-82, LL1:-18, LL2:28 }};
const tm1Angles = {{ LL1:-155, LL2:141, PreClean1:-90, PreClean2:90, PT1_S0:-17, PT1_S1:0, PT2_S0:13, PT2_S1:28 }};
const tm2Angles = {{ EPI1:-50, EPI2:-27, EPI3:10, EPI4:41, PT1_S0:-157, PT1_S1:180, PT2_S0:163, PT2_S1:144 }};

const ROBOT_MAP = {{
  ATM1: {{ angles:atmAngles, idleLen:20, busyLen:28 }},
  Robot1: {{ angles:tm1Angles, idleLen:30, busyLen:35, cssId:'TM1' }},
  Robot2: {{ angles:tm2Angles, idleLen:30, busyLen:35, cssId:'TM2' }}
}};

let replayIdx = 0, playing = false, speed = 1, animId = null;
let lastRealTime = 0, currentSimTime = 0;

function fmtTime(s) {{
  const h=Math.floor(s/3600),m=Math.floor((s%3600)/60),se=Math.floor(s%60);
  return String(h).padStart(2,'0')+':'+String(m).padStart(2,'0')+':'+String(se).padStart(2,'0');
}}

function replayToggle() {{ if (playing) replayPause(); else replayPlay(); }}

function replayPlay() {{
  if (!REPLAY.length || playing) return;
  playing = true;
  document.getElementById('rp-play').textContent = '⏸ 暂停';
  lastRealTime = performance.now();
  if (!currentSimTime) currentSimTime = REPLAY[replayIdx].currentTimeSec;
  animId = requestAnimationFrame(replayLoop);
}}

function replayPause() {{
  playing = false;
  document.getElementById('rp-play').textContent = '▶ 播放';
  if (animId) {{ cancelAnimationFrame(animId); animId = null; }}
}}

function replayLoop(now) {{
  if (!playing) return;
  const elapsed = (now - lastRealTime) / 1000;
  lastRealTime = now;
  currentSimTime += elapsed * speed;
  while (replayIdx < REPLAY.length - 1 && REPLAY[replayIdx + 1].currentTimeSec <= currentSimTime) {{
    replayIdx++;
  }}
  if (replayIdx >= REPLAY.length - 1) {{
    replayIdx = REPLAY.length - 1;
    currentSimTime = REPLAY[replayIdx].currentTimeSec;
    replayPause();
  }}
  renderFrame();
  if (playing) animId = requestAnimationFrame(replayLoop);
}}

function replayStep(dir) {{
  replayPause();
  if (!REPLAY.length) return;
  replayIdx = Math.max(0, Math.min(REPLAY.length - 1, replayIdx + dir));
  currentSimTime = REPLAY[replayIdx].currentTimeSec;
  renderFrame();
}}

function replaySpeed() {{ speed = parseFloat(document.getElementById('rp-speed').value); }}

function replaySeek() {{
  replayPause();
  if (!REPLAY.length) return;
  const pct = parseInt(document.getElementById('rp-progress').value) / 100;
  replayIdx = Math.floor(pct * (REPLAY.length - 1));
  currentSimTime = REPLAY[replayIdx].currentTimeSec;
  renderFrame();
}}

function renderFrame() {{
  if (!REPLAY.length) return;
  const snap = REPLAY[replayIdx];
  document.getElementById('rp-time').textContent = fmtTime(snap.currentTimeSec);
  document.getElementById('rp-progress').value = REPLAY.length > 1 ? (replayIdx / (REPLAY.length - 1) * 100) : 0;
  document.getElementById('rp-done').textContent = snap.completedWafers || 0;
  document.getElementById('rp-total').textContent = snap.totalWafers || 0;
  document.getElementById('rp-status-text').textContent = snap.status || '-';
  updateLayout(snap);
}}

function getRobotFromSnap(rbs, robotId) {{
  for (const [rid, r] of Object.entries(rbs || {{}})) {{
    if (rid === robotId || r.tmId === robotId) return r;
  }}
  return null;
}}

function getArmPhase(r) {{
  if (!r || r.state !== 'BUSY') return '';
  const total = 15;
  const remaining = r.remainingTimeSec || 0;
  return remaining > total / 2 ? (r.sourceChamber || '') : (r.targetChamber || '');
}}

function updateLayout(snap) {{
  const ch = snap.chambers || {{}};
  const rbs = snap.robots || {{}};
  const wfs = snap.wafers || [];

  // ---- FOUP slots ----
  const waferMap = {{}};
  for (const wf of wfs) {{
    waferMap[wf.foupIndex + '_' + wf.slotIndex] = wf;
  }}
  for (let fi = 0; fi < 3; fi++) {{
    for (let si = 0; si < 25; si++) {{
      const el = document.getElementById('f-' + FOUP_NAMES[fi] + '-' + si);
      if (!el) continue;
      const slotIndex = si + 1;
      const wf = waferMap[fi + '_' + slotIndex];
      let color = '#1a1a2e';
      if (wf) {{
        if (wf.state === 'COMPLETED') color = '#4CAF50';
        else if (CHAMBER_SET.has(wf.location)) color = '#1a1a2e';
        else color = '#555';
      }}
      el.setAttribute('fill', color);
    }}
  }}

  // ---- Chambers ----
  for (const cid of CHAMBER_IDS) {{
    const cd = ch[cid];
    const color = cd ? (STATE_COLORS[cd.state] || '#2a3a4a') : '#2a3a4a';

    // Fill color
    const rc = document.getElementById('rc-' + cid);
    if (rc) rc.setAttribute('fill', color);

    // State text (LL, PreClean, EPI)
    if (STATE_ID_IDS.has(cid)) {{
      const st = document.getElementById('st-' + cid);
      if (st) {{
        let txt = cd ? (STATE_LABELS[cd.state] || cd.state || '空闲') : '空闲';
        if (cd && cd.remainingTimeSec > 0) txt += ' ' + cd.remainingTimeSec + 's';
        st.textContent = txt;
      }}
    }}

    // Wafer text (PreClean, EPI, PT, Aligner)
    const wfEl = document.getElementById('wf-' + cid);
    if (wfEl) {{
      if (cid === 'ALIGNER' || cid.startsWith('PT')) {{
        wfEl.textContent = cd && cd.waferId ? cd.waferId : '空';
        wfEl.style.display = '';
      }} else {{
        wfEl.textContent = cd && cd.waferId ? cd.waferId : '';
        wfEl.style.display = cd && cd.waferId ? '' : 'none';
      }}
    }}

    // Wafer count (LL only)
    if (cid === 'LL1' || cid === 'LL2') {{
      const wc = document.getElementById('wc-' + cid);
      if (wc) wc.textContent = (cd ? cd.waferCount || 0 : 0) + '片';
    }}

    // Progress bar
    if (PROGRESS_IDS.has(cid)) {{
      const pb = document.getElementById('pb-' + cid);
      if (pb && cd && cd.totalTimeSec > 0 && cd.remainingTimeSec > 0) {{
        const maxW = cid.startsWith('EPI') ? 85 : 80;
        const pct = Math.max(0, Math.min(1, 1 - cd.remainingTimeSec / cd.totalTimeSec));
        pb.setAttribute('width', pct * maxW);
      }} else if (pb) {{
        pb.setAttribute('width', 0);
      }}
    }}

    // PT stroke / cooling indicator
    if (cid.startsWith('PT')) {{
      const isCooling = COOLING_SLOTS.has(cid);
      if (rc) rc.setAttribute('stroke', isCooling ? '#00BCD4' : '#FFEB3B');
      const pn = document.getElementById('ptname-' + cid);
      if (pn) pn.setAttribute('fill', isCooling ? '#80DEEA' : '#FFF176');
      const coolEl = document.getElementById('cool-' + cid);
      if (coolEl) coolEl.style.display = isCooling ? '' : 'none';
    }}
  }}

  // ---- Robots ----
  for (const [robotId, cfg] of Object.entries(ROBOT_MAP)) {{
    const rd = getRobotFromSnap(rbs, robotId);
    const cssId = cfg.cssId || robotId;
    const busy = rd && rd.state === 'BUSY';

    // State text
    const rbst = document.getElementById('rbst-' + cssId);
    if (rbst) {{
      if (rd) rbst.textContent = busy ? (rd.currentAction || '搬运中') : '空闲';
      else rbst.textContent = '离线';
    }}

    // Arm
    const phase = getArmPhase(rd);
    const angle = busy && phase ? (cfg.angles[phase] || 0) : 0;
    const len = busy ? cfg.busyLen : cfg.idleLen;

    const armG = document.getElementById('arm-' + cssId);
    if (armG) {{
      armG.style.transform = 'rotate(' + angle + 'deg)';
      armG.style.display = rd ? '' : 'none';
    }}

    const armL = document.getElementById('arml-' + cssId);
    if (armL) armL.setAttribute('x2', len);

    const armC = document.getElementById('armc-' + cssId);
    if (armC) armC.setAttribute('cx', len);

    const armW = document.getElementById('armw-' + cssId);
    if (armW) {{
      const waferId = rd && rd.arm1WaferId;
      armW.textContent = waferId || '';
      armW.style.display = waferId ? '' : 'none';
      armW.setAttribute('x', len);
    }}
  }}
}}

(function init() {{
  document.getElementById('rp-total').textContent = REPLAY.length > 0 ? REPLAY[REPLAY.length - 1].totalWafers : 0;
  renderFrame();
}})();
</script>

<h2>Wafer History</h2>
<div style="display:flex;align-items:center;gap:12px;margin:10px 0">
  <select id="waferSelect" onchange="showWaferHistory()" style="background:#16213e;color:#00d4ff;border:1px solid #333;padding:6px 10px;border-radius:4px;font-size:13px">
    <option value="">-- 选择 Wafer --</option>
    {wafer_history_options_html}
  </select>
</div>
<div id="waferHistoryTable" style="max-height:500px;overflow:auto">
  <p style="color:#888">选择上方 Wafer 查看完整调度历史</p>
</div>

{wafer_history_script}

<footer>EPI Scheduler v1.0 | 报告自动生成 | 总事件: {len(events)} 条</footer>
</body>
</html>'''

    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "result")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "simulation_report.html")
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write(html)
    print(f"Report saved to: {out_path}")
    print(f"  Wafers: {completed}/{total_wafers}")
    print(f"  WPH: {wph}")
    print(f"  Sim time: {format_time(sim_time)}")
    print(f"  Avg cycle: {format_time(avg_cycle)}")

if __name__ == '__main__':
    main()
