#!/usr/bin/env node
/*
 * Generates the SVG diagrams used by README.md / README_EN.md.
 *
 * Zero dependencies on purpose: the diagrams must stay reviewable and diffable, and
 * nobody should need a toolchain to regenerate them. Run:
 *
 *     node docs/images/generate.mjs
 *
 * Output: docs/images/touchpad-pipeline.svg
 *         docs/images/shizuku-vs-accessibility.svg
 *         docs/images/ethernet-priority.svg
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT_DIR = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------- primitives ---
const FONT = "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif";
const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace";

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** Rounded box with an optional title line. */
function box({ x, y, w, h, title, lines = [], fill = '#ffffff', stroke = '#d0d7de', titleColor = '#1f2328', radius = 10 }) {
    const parts = [
        `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${radius}" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>`,
    ];
    if (title) {
        parts.push(`<text x="${x + w / 2}" y="${y + 24}" text-anchor="middle" font-family="${FONT}" font-size="13" font-weight="600" fill="${titleColor}">${esc(title)}</text>`);
    }
    lines.forEach((line, i) => {
        parts.push(`<text x="${x + w / 2}" y="${y + (title ? 48 : lines.length === 1 ? 30 : 26) + i * 18}" text-anchor="middle" font-family="${MONO}" font-size="11.5" fill="#57606a">${esc(line)}</text>`);
    });
    return parts.join('\n  ');
}

function label({ x, y, text, size = 12, weight = 400, color = '#1f2328', anchor = 'start', family = FONT }) {
    return `<text x="${x}" y="${y}" text-anchor="${anchor}" font-family="${family}" font-size="${size}" font-weight="${weight}" fill="${color}">${esc(text)}</text>`;
}

function arrow({ x1, y1, x2, y2, color = '#57606a', dashed = false, label: text = null, labelDx = 0, labelDy = -8 }) {
    const head = 8;
    const angle = Math.atan2(y2 - y1, x2 - x1);
    const hx1 = x2 - head * Math.cos(angle - Math.PI / 7);
    const hy1 = y2 - head * Math.sin(angle - Math.PI / 7);
    const hx2 = x2 - head * Math.cos(angle + Math.PI / 7);
    const hy2 = y2 - head * Math.sin(angle + Math.PI / 7);
    const parts = [
        `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${color}" stroke-width="1.8" marker-end="none"${dashed ? ' stroke-dasharray="6 5"' : ''}/>`,
        `<polygon points="${x2},${y2} ${hx1},${hy1} ${hx2},${hy2}" fill="${color}"/>`,
    ];
    if (text) {
        parts.push(label({ x: (x1 + x2) / 2 + labelDx, y: (y1 + y2) / 2 + labelDy, text, size: 11, color, anchor: 'middle' }));
    }
    return parts.join('\n  ');
}

function badge({ x, y, text, fill = '#1a7f37', color = '#ffffff', w = null }) {
    const width = w ?? Math.max(46, text.length * 8 + 20);
    return [
        `<rect x="${x}" y="${y}" width="${width}" height="20" rx="10" fill="${fill}"/>`,
        `<text x="${x + width / 2}" y="${y + 14}" text-anchor="middle" font-family="${FONT}" font-size="11" font-weight="600" fill="${color}">${esc(text)}</text>`,
    ].join('\n  ');
}

function svg({ width, height, body }) {
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img">
  <rect width="${width}" height="${height}" rx="14" fill="#f6f8fa"/>
  ${body}
</svg>
`;
}

// -------------------------------------------------------- 1. touchpad pipeline ---
function touchpadPipeline() {
    const W = 960;
    const H = 440;
    const rows = [
        {
            y: 54,
            name: 'Android 16 / ColorOS 16',
            path: ['Pointer capture', 'SOURCE_TOUCHPAD', 'pc = 2 / 3 / 4'],
            note: '原始多指帧直达应用',
            ok: true,
        },
        {
            y: 176,
            name: 'Android 17 / ColorOS 17（未适配）',
            path: ['Pointer capture', 'SOURCE_MOUSE_RELATIVE', 'pc = 1 恒为单指'],
            note: '多指帧消失：滚动变滚轮、轻点变点按',
            ok: false,
        },
        {
            y: 298,
            name: '本项目（ColorOS 17 适配）',
            path: ['Shizuku 直读 evdev', '合成 SOURCE_TOUCHPAD', 'pc = 2 / 3 / 4 保留'],
            note: '手势算法零改动，手感一致',
            ok: true,
        },
    ];

    const parts = [
        label({ x: 28, y: 32, text: '触控板事件管线：同一套手势识别，三种数据来源', size: 15, weight: 700 }),
    ];

    rows.forEach((row) => {
        parts.push(box({ x: 28, y: row.y, w: 300, h: 84, title: row.name, fill: row.ok ? '#ffffff' : '#fff5f5', stroke: row.ok ? '#d0d7de' : '#ffb3b3' }));
        row.path.forEach((step, i) => {
            const bx = 352 + i * 196;
            parts.push(box({ x: bx, y: row.y + 14, w: 178, h: 56, title: null, lines: [step], fill: '#ffffff', stroke: row.ok ? '#7cc4a0' : '#ffb3b3' }));
            if (i === 0) {
                parts.push(arrow({ x1: 332, y1: row.y + 42, x2: 350, y2: row.y + 42 }));
            } else {
                parts.push(arrow({ x1: bx - 20, y1: row.y + 42, x2: bx - 2, y2: row.y + 42 }));
            }
        });
        parts.push(box({ x: 744, y: row.y + 14, w: 188, h: 56, title: null, lines: ['1/2/3/4 指手势'], fill: row.ok ? '#eaf7ef' : '#fff5f5', stroke: row.ok ? '#7cc4a0' : '#ffb3b3' }));
        parts.push(arrow({ x1: 728, y1: row.y + 42, x2: 742, y2: row.y + 42 }));
        parts.push(label({ x: 838, y: row.y + 84, text: row.note, size: 11, anchor: 'middle', color: row.ok ? '#1a7f37' : '#cf222e' }));
    });

    parts.push(label({ x: 480, y: 418, text: '关键：ColorOS 17 捕获态下 pc 恒为 1，多指信息在系统层已被合成为滚轮 / 点按', size: 11.5, anchor: 'middle', color: '#57606a' }));

    return svg({ width: W, height: H, body: parts.join('\n  ') });
}

// ------------------------------------------------- 2. shizuku vs accessibility ---
function shizukuVsAccessibility() {
    const W = 960;
    const H = 400;
    const parts = [
        label({ x: 28, y: 32, text: '键盘 / 触控板捕获：Shizuku 与无障碍服务的差别', size: 15, weight: 700 }),
    ];

    // left column: accessibility
    parts.push(box({ x: 28, y: 56, w: 430, h: 312, title: '无障碍服务（AccessibilityService）', fill: '#fff8f0', stroke: '#f0c48a' }));
    [
        'App 注册无障碍服务，由系统转发按键',
        '事件仍经过系统输入栈',
        'OEM 快捷键（如 OPPO Win→AI）照常触发',
        '无法独占设备，Esc 等键行为不稳定',
    ].forEach((t, i) => {
        parts.push(label({ x: 52, y: 108 + i * 30, text: '·  ' + t, size: 12, color: '#57606a' }));
    });
    parts.push(label({ x: 52, y: 246, text: '权限路径：设置 → 无障碍', size: 11, family: MONO, color: '#57606a' }));
    parts.push(box({ x: 52, y: 300, w: 382, h: 48, title: null, lines: ['系统输入栈 → 应用 → 串流'], fill: '#ffffff', stroke: '#f0c48a' }));

    // right column: shizuku
    parts.push(box({ x: 502, y: 56, w: 430, h: 312, title: 'Shizuku UserService（本项目采用）', fill: '#eef7f1', stroke: '#7cc4a0' }));
    [
        '以 shell / root 身份常驻独立进程',
        '对 /dev/input/event* 执行 EVIOCGRAB 独占',
        '事件不再进入系统 → OEM 快捷键不触发',
        'Esc、Fn 组合、触控板原样交给串流主机',
    ].forEach((t, i) => {
        parts.push(label({ x: 526, y: 108 + i * 30, text: '·  ' + t, size: 12, color: '#57606a' }));
    });
    parts.push(label({ x: 526, y: 246, text: '权限路径：Shizuku（ADB / 无线调试授权一次）', size: 11, family: MONO, color: '#57606a' }));
    parts.push(box({ x: 526, y: 300, w: 382, h: 48, title: null, lines: ['evdev 独占 → 应用 → 串流'], fill: '#ffffff', stroke: '#7cc4a0' }));

    parts.push(arrow({ x1: 470, y1: 212, x2: 490, y2: 212, color: '#afb8c1' }));
    parts.push(badge({ x: 502, y: 72, text: '推荐', fill: '#1a7f37' }));
    parts.push(label({ x: 28, y: 388, text: '安全阀：fd 关闭或 UserService 进程退出时内核自动释放 EVIOCGRAB，系统输入立即恢复。', size: 11.5, color: '#57606a' }));

    return svg({ width: W, height: H, body: parts.join('\n  ') });
}

// ------------------------------------------------------- 3. ethernet priority ---
function ethernetPriority() {
    const W = 960;
    const H = 330;
    const parts = [
        label({ x: 28, y: 32, text: '有线优先：只把串流流量绑到有线网卡', size: 15, weight: 700 }),
        box({ x: 28, y: 60, w: 250, h: 96, title: 'App 默认网络（系统）', lines: ['Wi-Fi 保持连接'], fill: '#ffffff' }),
        box({ x: 28, y: 186, w: 250, h: 96, title: '串流进程绑定', lines: ['bindProcessToNetwork(eth)'], fill: '#eef7f1', stroke: '#7cc4a0' }),
        box({ x: 372, y: 60, w: 236, h: 96, title: 'Wi-Fi / 蜂窝', lines: ['主机发现 · 其他流量'], fill: '#ffffff' }),
        box({ x: 372, y: 186, w: 236, h: 96, title: '有线网卡（USB-C / 扩展坞）', lines: ['eth0 · 千兆内网'], fill: '#ffffff', stroke: '#7cc4a0' }),
        box({ x: 716, y: 122, w: 216, h: 100, title: '串流主机', lines: ['Apollo / Sunshine'], fill: '#ffffff' }),
    ];

    parts.push(arrow({ x1: 280, y1: 108, x2: 368, y2: 108 }));
    parts.push(arrow({ x1: 280, y1: 234, x2: 368, y2: 234, color: '#1a7f37' }));
    parts.push(arrow({ x1: 610, y1: 234, x2: 712, y2: 200, color: '#1a7f37' }));
    parts.push(arrow({ x1: 610, y1: 108, x2: 712, y2: 146, color: '#afb8c1', dashed: true }));
    parts.push(label({ x: 660, y: 128, text: '发现 / 配对', size: 10.5, color: '#57606a', anchor: 'middle' }));
    parts.push(label({ x: 660, y: 262, text: '视频 / 音频 / 输入', size: 10.5, color: '#1a7f37', anchor: 'middle' }));
    parts.push(label({ x: 28, y: 310, text: 'bionic 的 socket()/connect() 会应用进程默认网络，因此 moonlight-common-c 的原生 socket 也走有线。', size: 11.5, color: '#57606a' }));

    return svg({ width: W, height: H, body: parts.join('\n  ') });
}

// -------------------------------------------------------------------- emit ----
const files = {
    'touchpad-pipeline.svg': touchpadPipeline(),
    'shizuku-vs-accessibility.svg': shizukuVsAccessibility(),
    'ethernet-priority.svg': ethernetPriority(),
};

mkdirSync(OUT_DIR, { recursive: true });
for (const [name, content] of Object.entries(files)) {
    writeFileSync(join(OUT_DIR, name), content);
    console.log(`wrote docs/images/${name} (${content.length} bytes)`);
}
