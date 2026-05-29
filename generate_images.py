#!/usr/bin/env python3
"""Generate PNG images for PPT: UI mockup, Gantt chart, chamber utilization."""

import json, urllib.request, os
from PIL import Image, ImageDraw, ImageFont

API = "http://localhost:8080/api"
OUT = os.path.dirname(os.path.abspath(__file__))

def fetch(endpoint):
    with urllib.request.urlopen(f"{API}{endpoint}") as r:
        return json.loads(r.read())

def try_font(size):
    for name in ['/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
                 '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
                 '/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf']:
        if os.path.exists(name):
            return ImageFont.truetype(name, size)
    return ImageFont.load_default()

def format_time(sec):
    m, s = divmod(sec, 60)
    return f"{m}:{s:02d}"

def generate_ui_mockup():
    """Generate a simplified UI layout mockup."""
    W, H = 900, 520
    img = Image.new('RGB', (W, H), (0x1A, 0x1A, 0x2E))
    draw = ImageDraw.Draw(img)
    font = try_font(9)
    font_s = try_font(7)

    # Header
    draw.rectangle([0, 0, W, 36], fill=(0x16, 0x21, 0x3E))
    draw.text((12, 8), "EPI Semiconductor Cluster Tool Scheduler", fill=(0x00, 0xD4, 0xFF), font=try_font(14))
    draw.text((700, 12), "Time: 04:22:22 | Status: COMPLETED | 25/25 | WPH:5.7", fill=(0x88, 0x88, 0x88), font=font_s)

    # Control bar
    draw.rectangle([0, 36, W, 60], fill=(0x0F, 0x34, 0x60))
    btns = [("▶ Start", 0x4C, 0xAF, 0x50), ("⏸ Pause", 0xFF, 0x98, 0x00),
            ("⏭ Step", 0x00, 0xD4, 0xFF), ("↺ Reset", 0xE9, 0x1E, 0x63)]
    for i, (label, r, g, b) in enumerate(btns):
        x = 12 + i * 90
        draw.rounded_rectangle([x, 40, x+80, 56], radius=4, fill=(r, g, b))
        draw.text((x+15, 43), label, fill=(0xFF, 0xFF, 0xFF), font=font_s)

    # Left panel - SVG layout area
    draw.rectangle([0, 60, 580, H], outline=(0x33, 0x33, 0x33))
    draw.text((260, 62), "Tool Layout (SVG)", fill=(0x88, 0x88, 0x88), font=font_s)

    # FOUPs
    colors_foup = [(0x55, 0x55, 0x55), (0x1A, 0x1A, 0x2E), (0x4C, 0xAF, 0x50)]
    for fi in range(3):
        fx, fy = 10 + fi * 90, 80
        draw.text((fx+30, fy-12), f"FOUP{fi+1}", fill=(0x88, 0x88, 0x88), font=font_s)
        for r in range(5):
            for c in range(5):
                x, y = fx + c*15, fy + r*15
                color = colors_foup[0] if fi == 0 else colors_foup[2] if fi == 1 else colors_foup[1]
                draw.rectangle([x, y, x+13, y+13], fill=color, outline=(0x33, 0x33, 0x33))

    # LL
    for i, lid in enumerate(['LL1', 'LL2']):
        ly = 80 + i * 60
        color = (0x00, 0x60, 0x64) if i == 0 else (0x2A, 0x3A, 0x4A)
        draw.rounded_rectangle([290, ly, 370, ly+40], radius=6, fill=color, outline=(0x21, 0x96, 0xF3))
        draw.text((300, ly+5), lid, fill=(0x64, 0xB5, 0xF6), font=font_s)
        draw.text((300, ly+18), "IDLE" if i == 1 else "IDLE 0/25", fill=(0xFF, 0xFF, 0xFF), font=font_s)

    # PreClean
    for i, pc in enumerate(['PreClean1', 'PreClean2']):
        py = 80 + i * 60
        draw.rounded_rectangle([390, py, 460, py+40], radius=6, fill=(0x2A, 0x3A, 0x4A), outline=(0xFF, 0x98, 0x00))
        draw.text((395, py+5), pc, fill=(0xFF, 0xB7, 0x4D), font=font_s)
        draw.text((395, py+18), "IDLE", fill=(0xFF, 0xFF, 0xFF), font=font_s)

    # PT slots
    pt_data = [("PT1_S0", 160, True), ("PT1_S1", 200, False), ("PT2_S0", 250, False), ("PT2_S1", 290, True)]
    for label, py, cool in pt_data:
        color = (0x42, 0xA5, 0xF5) if cool else (0xFF, 0xEB, 0x3B)
        text_c = (0x80, 0xDE, 0xEA) if cool else (0xFF, 0xF1, 0x76)
        draw.rounded_rectangle([480, py, 530, py+30], radius=4, fill=(0x1A, 0x2A, 0x3A), outline=color)
        draw.text((485, py+3), label, fill=text_c, font=try_font(7))
        draw.text((485, py+15), "空", fill=(0xFF, 0xFF, 0xFF), font=font_s)
        if cool:
            draw.text((516, py+3), "❄", fill=(0x00, 0xBC, 0xD4), font=font_s)

    # TM circle
    draw.ellipse([555, 160, 610, 215], fill=(0x1A, 0x2A, 0x3A), outline=(0xE9, 0x1E, 0x63))
    draw.text((563, 180), "TM2", fill=(0xE9, 0x1E, 0x63), font=try_font(8))

    # EPI
    for i, epi in enumerate(['EPI1', 'EPI2', 'EPI3', 'EPI4']):
        ey = 70 + i * 100
        draw.rounded_rectangle([630, ey, 700, ey+45], radius=6, fill=(0x2A, 0x3A, 0x4A), outline=(0x4C, 0xAF, 0x50))
        draw.text((635, ey+5), epi, fill=(0x81, 0xC7, 0x84), font=font_s)
        draw.text((635, ey+18), "IDLE", fill=(0xFF, 0xFF, 0xFF), font=font_s)

    # Right panel - event log
    draw.rectangle([580, 60, W, H], outline=(0x33, 0x33, 0x33))
    draw.text((590, 62), "Event Log", fill=(0x00, 0xD4, 0xFF), font=try_font(10))
    for i, evt in enumerate(["LL1 loading 25 wafers batch", "LL1 batch loaded, pumping down",
                              "LL1 pump complete, ready", "TM1: moved W1 from LL1 to PreClean1",
                              "PreClean1 processing done for W1", "TM1: moved W1 to PT1_S0",
                              "TM2: moved W1 from PT1_S0 to EPI1", "..."]):
        draw.text((590, 80 + i*16), evt, fill=(0xA0, 0xD0, 0xA0), font=try_font(8))

    path = os.path.join(OUT, "ui_mockup.png")
    img.save(path)
    return path

def generate_gantt_chart():
    """Generate Gantt chart as PNG."""
    gantt = fetch("/simulation/gantt")
    state = fetch("/simulation/state")
    sim_time = state['currentTimeSec']

    W, H = 1100, 360
    img = Image.new('RGB', (W, H), (0x1A, 0x1A, 0x2E))
    draw = ImageDraw.Draw(img)
    font = try_font(7)

    # Colors
    colors = {
        'PRECLEAN': (0xFF, 0x98, 0x00), 'EPI': (0x4C, 0xAF, 0x50),
        'PASSTHROUGH': (0xFF, 0xEB, 0x3B), 'PT_RETURN': (0xE9, 0x1E, 0x63),
        'LOADLOCK': (0x21, 0x96, 0xF3), 'LOADLOCK_RET': (0x9C, 0x27, 0xB0),
    }
    slot_c = {
        'PT1_S0': ((0x42, 0xA5, 0xF5), (0x15, 0x65, 0xC0)),
        'PT1_S1': ((0xFF, 0xEB, 0x3B), (0xF9, 0xA8, 0x25)),
        'PT2_S0': ((0xFF, 0xEB, 0x3B), (0xF9, 0xA8, 0x25)),
        'PT2_S1': ((0x42, 0xA5, 0xF5), (0x15, 0x65, 0xC0)),
    }

    chamber_order = ['LL1', 'LL2', 'PreClean1', 'PreClean2',
                     'PT1_S0', 'PT1_S1', 'PT2_S0', 'PT2_S1',
                     'EPI1', 'EPI2', 'EPI3', 'EPI4']
    row_h = 26
    margin_left = 80
    chart_left = margin_left + 15
    chart_w = W - chart_left - 20
    scale = chart_w / max(sim_time, 1)

    # Filter entries
    entries = [e for e in gantt if e['waferId'] not in ('BATCH',) and not e['waferId'].startswith('BATCH')]

    # Time ruler
    for i in range(11):
        t = int(i * sim_time / 10)
        x = chart_left + t * scale
        draw.line([(x, 18), (x, 22)], fill=(0x66, 0x66, 0x66))
        draw.text((x-15, 2), format_time(t), fill=(0x66, 0x66, 0x66), font=font)

    # Rows
    for ri, ch in enumerate(chamber_order):
        y = 25 + ri * row_h
        # Row bg
        draw.rectangle([chart_left, y+1, W-20, y+row_h-1], fill=(0x0D, 0x11, 0x17))
        # Label + utilization
        ch_entries = [e for e in entries if e['location'] == ch]
        occ = sum(max(0, (e['endTimeSec'] if e['endTimeSec']>0 else sim_time) - e['startTimeSec']) for e in ch_entries)
        util = min(100, round(occ * 100 / sim_time))
        draw.text((4, y+4), f"{ch}", fill=(0xAA, 0xAA, 0xAA), font=font)
        draw.text((margin_left-28, y+4), f"{util}%", fill=(0x00, 0xD4, 0xFF), font=try_font(6))

        # Bars
        for e in ch_entries:
            start = e['startTimeSec']
            end = e['endTimeSec'] if e['endTimeSec'] > 0 else sim_time
            sx = chart_left + start * scale
            ex = chart_left + end * scale
            if ex - sx < 1: continue
            loc = e['location']
            etype = e['type']
            if loc in slot_c:
                color = slot_c[loc][1] if etype == 'PT_RETURN' else slot_c[loc][0]
            else:
                color = colors.get(etype, (0x66, 0x66, 0x66))
            draw.rectangle([sx, y+3, ex, y+row_h-3], fill=color)

    path = os.path.join(OUT, "gantt_chart.png")
    img.save(path)
    return path

def generate_util_chart():
    """Generate chamber utilization bar chart."""
    gantt = fetch("/simulation/gantt")
    state = fetch("/simulation/state")
    sim_time = state['currentTimeSec']

    chambers = ['LL1', 'LL2', 'PreClean1', 'PreClean2',
                'PT1_S0', 'PT1_S1', 'PT2_S0', 'PT2_S1',
                'EPI1', 'EPI2', 'EPI3', 'EPI4']
    entries = [e for e in gantt if e['waferId'] not in ('BATCH',) and not e['waferId'].startswith('BATCH')]
    utils = {}
    for ch in chambers:
        ch_e = [e for e in entries if e['location'] == ch]
        occ = sum(max(0, (e['endTimeSec'] if e['endTimeSec']>0 else sim_time) - e['startTimeSec']) for e in ch_e)
        utils[ch] = min(100, round(occ * 100 / sim_time))

    W, H = 800, 320
    img = Image.new('RGB', (W, H), (0x1A, 0x1A, 0x2E))
    draw = ImageDraw.Draw(img)
    font = try_font(9)
    font_s = try_font(7)

    bar_w = 50
    spacing = 16
    base_y = H - 50
    max_h = H - 100

    for i, ch in enumerate(chambers):
        x = 50 + i * (bar_w + spacing)
        u = utils.get(ch, 0)
        bar_h = int(u * max_h / 100)
        color = (0x00, 0xD4, 0xFF)
        if 'PreClean' in ch: color = (0xFF, 0x98, 0x00)
        elif 'PT' in ch: color = (0xFF, 0xEB, 0x3B)
        elif 'EPI' in ch: color = (0x4C, 0xAF, 0x50)
        elif 'LL' in ch: color = (0x21, 0x96, 0xF3)
        draw.rectangle([x, base_y-bar_h, x+bar_w, base_y], fill=color, outline=(0x33, 0x33, 0x33))
        draw.text((x+2, base_y - bar_h - 14), f"{u}%", fill=(0xFF, 0xFF, 0xFF), font=font_s)
        # Label rotated
        label = ch.replace('_S', '/')
        draw.text((x+5, base_y + 5), label, fill=(0xAA, 0xAA, 0xAA), font=try_font(7))

    # Title
    draw.text((W//2 - 80, 8), "Chamber Utilization (%)", fill=(0x00, 0xD4, 0xFF), font=try_font(14))

    path = os.path.join(OUT, "util_chart.png")
    img.save(path)
    return path

if __name__ == '__main__':
    print("Generating UI mockup...")
    p1 = generate_ui_mockup()
    print(f"  {p1}")
    print("Generating Gantt chart...")
    p2 = generate_gantt_chart()
    print(f"  {p2}")
    print("Generating utilization chart...")
    p3 = generate_util_chart()
    print(f"  {p3}")
    print("Done.")
