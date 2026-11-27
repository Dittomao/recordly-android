"use strict";

// All copy and media are fixture data. Nothing is read from the device or saved.
const paths = {
  add: '<path d="M12 5v14M5 12h14"/>',
  arrow: '<path d="m10 6 6 6-6 6"/>',
  audio: '<path d="M3 10v4m4-7v10m5-14v18m5-14v10m4-7v4"/>',
  back: '<path d="m14 6-6 6 6 6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  clips: '<rect x="4" y="5" width="16" height="14" rx="2"/><path d="M8 5v14m8-14v14"/>',
  down: '<path d="M12 4v11m-4-4 4 4 4-4M5 16v4h14v-4"/>',
  fit: '<path d="M9 4H4v5m11-5h5v5M4 15v5h5m11-5v5h-5"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  frame: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 9h8v6H8z"/>',
  home: '<path d="m3 10 9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
  import: '<path d="M12 3v12m-4-4 4 4 4-4M5 15v5h14v-5"/>',
  mic: '<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0m-7 7v3"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  pause: '<path d="M9 5H7v14h2zm8 0h-2v14h2z" fill="currentColor"/>',
  play: '<path d="m8 5 11 7-11 7z" fill="currentColor"/>',
  record: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3.4" fill="currentColor" stroke="none"/>',
  search: '<circle cx="10.5" cy="10.5" r="5.5"/><path d="m15 15 5 5"/>',
  settings: '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="2.5" fill="var(--bg)"/><circle cx="15" cy="17" r="2.5" fill="var(--bg)"/>',
  split: '<path d="M12 4v16m-3-13H5a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h4m6-10h4a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-4"/>',
  trim: '<path d="M7 5H4v14h3m10-14h3v14h-3M9 12h6"/>',
  undo: '<path d="M9 7 4 12l5 5m-5-5h12a4 4 0 0 1 4 4v2"/>',
  zoom: '<circle cx="10.5" cy="10.5" r="5.5"/><path d="m15 15 5 5m-9.5-12v5m-2.5-2.5h5"/>',
};

const projects = [
  { id: "metro", name: "Metro app walkthrough", time: 24, date: "Edited today", kind: "metro", zoom: 3 },
  { id: "clock", name: "Clock tutorial", time: 15, date: "Edited yesterday", kind: "clock", zoom: 2 },
  { id: "settings", name: "Quick settings guide", time: 38, date: "Edited Nov 18", kind: "settings", zoom: 4 },
  { id: "alarm", name: "Set a morning alarm", time: 19, date: "Edited Nov 16", kind: "clock", zoom: 2 },
  { id: "wifi", name: "Wi-Fi setup", time: 31, date: "Edited Nov 14", kind: "settings", zoom: 3 },
];
const directions = {
  studio: "A quiet, dark workspace. Record is always within reach; the editor puts the preview first and expands tools only when needed.",
  pocket: "A warmer, guided flow. Resume your last edit from Home, then use a bottom tool panel that explains what’s selected.",
  cut: "A more precise workbench. A compact library leads to an editor with named tracks and the active edit controls always in view.",
};
const tools = {
  trim: { label: "Trim", hint: "Keep or split the selected clip", action: "Split here" },
  zoom: { label: "Zoom", hint: "Fine-tune tap-driven focus", action: "Add zoom" },
  frame: { label: "Look", hint: "Adjust the frame and background", action: "Change look" },
  audio: { label: "Audio", hint: "Adjust microphone audio", action: "Volume" },
  clips: { label: "Clips", hint: "Append another recording", action: "Add clip" },
};
const state = {
  theme: "cut", screen: "home", project: projects[0], seconds: 6.4,
  playing: false, tool: null, library: "projects", query: "", expanded: false, zooms: 3, splits: [], edits: [], fit: false,
};
const $ = (selector) => document.querySelector(selector);
const time = (seconds) => `0:${String(Math.floor(seconds)).padStart(2, "0")}`;
const precise = (seconds) => `${time(seconds)}.${Math.floor((seconds % 1) * 10)}`;
const icon = (name) => `<svg class="icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
const iconButton = (name, label, action = name, extra = "") => `<button type="button" class="icon-button ${action === "play" ? "play-button" : ""}" aria-label="${label}" title="${label}" data-action="${action}" ${extra}>${icon(name)}</button>`;

const lineText = (x, y, copy, size = 7, weight = 600, fill = "#273c52") => `<text x="${x}" y="${y}" font-family="DM Sans, Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="${fill}">${copy}</text>`;
const svgScreen = {
  metro: `<rect width="112" height="210" rx="9" fill="#f5f9fc"/>
    <rect y="25" width="112" height="115" fill="#e3edea"/>
    <path d="M-9 49l28 9 40-21 57 7M-5 65l46 12 25-17 52 11M-12 86l33 9 26-9 25 11 46-19M-4 114l33 5 42-9 44 8M17 27l-6 43 1 31 12 41m17-116 1 28 15 29-8 57m27-116-9 28 11 44 10 47m12-116-8 28 8 42-1 45" fill="none" stroke="#fbfefe" stroke-width="2.5"/>
    <path d="M-8 127c31-1 49-10 69-33s25-37 56-45" fill="none" stroke="#abd7e6" stroke-width="10"/>
    <path d="M26 109V86q0-7 7-7h21l15-15V46" fill="none" stroke="#245da6" stroke-linecap="round" stroke-linejoin="round" stroke-width="3.6"/>
    <circle cx="26" cy="108" r="3.2" fill="white" stroke="#245da6" stroke-width="1.7"/><circle cx="49" cy="79" r="2.2" fill="white"/><circle cx="69" cy="46" r="3.5" fill="#245da6" stroke="white" stroke-width="1.8"/>
    ${lineText(9, 11, "9:41", 4)}<path d="M93 9h10m-7 2h7" stroke="#273c52" stroke-width="1.3"/>
    <path d="M9 20h8m-8 2h8" stroke="#273c52" stroke-width="1"/>
    ${lineText(23, 22, "Metro", 8.5, 800)}
    ${lineText(35, 116, "Central", 5.1)}${lineText(74, 41, "North Park", 4.7)}
    <rect x="5" y="132" width="102" height="72" rx="7" fill="white"/>
    <rect x="45" y="136" width="22" height="2" rx="1" fill="#d4dfe7"/>
    ${lineText(12, 151, "Your trip", 8.6, 800)}
    <circle cx="15" cy="160" r="2.2" fill="#4c7daf"/><circle cx="15" cy="171" r="2.2" fill="none" stroke="#4c7daf"/>
    <path d="M15 163v5" stroke="#4c7daf"/>
    ${lineText(22, 162, "Central Station", 5.8)}${lineText(22, 173, "North Park", 5.8)}
    <rect x="12" y="179" width="88" height="15" rx="4" fill="#204e84"/>${lineText(36, 189, "View route", 6.6, 750, "#fff")}
    <rect x="42" y="205" width="28" height="1.6" rx=".8" fill="#444d55"/>
    <g class="tap-ring" fill="none" stroke="#2563eb"><circle cx="81" cy="187" r="6"/><circle cx="81" cy="187" r="10" opacity=".6"/></g>`,
  clock: `<rect width="112" height="210" rx="9" fill="#232131"/>
    ${lineText(9, 12, "9:41", 4, 700, "white")}${lineText(10, 30, "Clock", 12, 750, "#f6f2ff")}
    <rect x="8" y="43" width="96" height="42" rx="8" fill="#373348"/><rect x="8" y="90" width="96" height="43" rx="8" fill="#373348"/>
    ${lineText(15, 68, "07:30", 16, 700, "#f6f2ff")}${lineText(15, 115, "08:15", 16, 700, "#f6f2ff")}
    <rect x="76" y="56" width="21" height="11" rx="5.5" fill="#d9c6fa"/><circle cx="91" cy="61.5" r="4" fill="#554579"/>
    <rect x="76" y="103" width="21" height="11" rx="5.5" fill="#5b546e"/><circle cx="81" cy="108.5" r="4" fill="#e4dded"/>
    <circle cx="56" cy="164" r="12" fill="#d9c6fa"/><path d="M50 164h12m-6-6v12" stroke="#463566" stroke-width="2"/>
    ${lineText(43, 190, "Alarms", 7.5, 750, "#e5d8fc")}<rect x="42" y="204" width="28" height="1.6" rx=".8" fill="#bfb5cb"/>
    <g class="tap-ring" fill="none" stroke="#72aaff"><circle cx="91" cy="61.5" r="7"/><circle cx="91" cy="61.5" r="10" opacity=".6"/></g>`,
  settings: `<rect width="112" height="210" rx="9" fill="#ecf3f1"/>
    ${lineText(9, 12, "9:41", 4)}${lineText(10, 33, "Settings", 12, 750, "#24453e")}
    <rect x="9" y="43" width="94" height="14" rx="6" fill="#dce9e4"/>
    ${lineText(17, 52, "Search settings", 5.6, 700, "#36574e")}
    ${["Network &amp; internet", "Connected devices", "Apps", "Notifications", "Battery"].map((label, i) =>
      `<circle cx="17" cy="${69 + 23 * i}" r="6" fill="#b6d6c9"/>${lineText(28, 71 + 23 * i, label, 6.3, 700, "#24453e")}<path d="M27 ${80 + 23 * i}h74" stroke="#d8e7e2"/>`).join("")}
    <rect x="42" y="204" width="28" height="1.6" rx=".8" fill="#526c64"/>
    <g class="tap-ring" fill="none" stroke="#2563eb"><circle cx="90" cy="70" r="6"/><circle cx="90" cy="70" r="10" opacity=".6"/></g>`,
};

const systemBars = {
  top: `<div class="status" aria-hidden="true"><span>9:41</span><svg viewBox="0 0 43 12" fill="currentColor"><path d="M1 4a9 9 0 0 1 13 0l-6.5 7zM26 1v10H16zM31 2h10v9H31zM41 5h2v3h-2z"/></svg></div>`,
  bottom: '<div class="gesture" aria-hidden="true"></div>',
};
const cover = (project, isHero = false, beat = 0) => `<span class="cover ${project.kind}" data-beat="${beat}" aria-hidden="true">
  <svg width="112" height="210" viewBox="0 0 112 210">${svgScreen[project.kind]}</svg>
  ${isHero ? `<span class="duration">${time(project.time)}</span>` : ""}</span>`;

const wordmark = '<span class="wordmark"><img src="../../recordly-upstream/public/app-icons/recordly-mark.svg" alt="">TapSwoop</span>';
const projectText = (project, titleClass) => `<span class="${titleClass}"><strong>${project.name}</strong><small>${time(project.time)} · ${project.date}</small></span>`;
const hero = (project, resume = false) => `<button type="button" class="hero-project" data-project="${project.id}" aria-label="Open ${project.name}">
  ${cover(project, true)}<span class="hero-caption"><span class="hero-text"><strong>${project.name}</strong><small>${resume ? "Resume at 0:06" : project.date}</small></span>${icon(resume ? "play" : "arrow")}</span></button>`;
const rows = (items) => `<div class="project-list">${items.map((project) => `<div class="project-row">
  <button type="button" class="project-open" data-project="${project.id}" aria-label="Open ${project.name}"><span class="project-thumb">${cover(project)}</span>${projectText(project, "project-text")}</button>
  ${iconButton("more", `Options for ${project.name}`, "project-options", `data-id="${project.id}"`)}</div>`).join("")}</div>`;
const record = (label) => `<button type="button" class="filled" data-action="record">${icon("record")}${label}</button>`;
// From Uiverse.io by vinodjangid07 - https://uiverse.io/vinodjangid07/smart-emu-83
// MIT License, Copyright (c) vinodjangid07 (Vinod Jangid)
const cutRecord = `<div class="cut-record-slot"><button type="button" class="cut-record-action" data-action="record" aria-label="New recording">
  <span class="cut-record-action__face" aria-hidden="true">
    <svg class="cut-record-action__glyph" width="12" height="14" viewBox="0 0 24 27.4">
      <circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="2"/>
      <circle cx="12" cy="12" r="4.5" fill="currentColor"/>
    </svg>
    <span class="cut-record-action__reveal">New recording</span>
  </span>
  <span class="cut-record-action__caption" aria-hidden="true">New recording</span>
</button></div>`;
const pickFile = '<button type="button" class="import-button" data-action="import">' + icon("import") + 'Import</button>';
const cutImport = `<button type="button" class="cut-import-action" data-action="import">
  <span class="cut-import-action__face">Import${icon("import")}</span>
</button>`;
const appbar = `<header class="appbar">${wordmark}${iconButton("settings", "Settings")}</header>`;
const recent = (items) => `<section aria-label="Recent projects">
  <div class="section-head"><h3>Recent projects</h3><button type="button" class="text-button" data-action="library">${state.expanded ? "Show less" : "See all"}</button></div>
  ${rows(state.expanded ? projects.slice(1) : items)}</section>`;

function updateLibrary() {
  const recordings = state.library === "recordings";
  const items = recordings ? projects.slice(0, 3).map(project => ({ ...project, name: `${project.name} · source`, date: "Original" })) : projects;
  const matches = items.filter(item => item.name.toLowerCase().includes(state.query.trim().toLowerCase()));
  const noun = recordings ? "recording" : "project";
  $("#library-items").innerHTML = `${rows(matches)}<p class="library-note" role="status">${matches.length ? `${matches.length} ${noun}${matches.length === 1 ? "" : "s"} · Stored on this device` : `No matching ${noun}s`}</p>`;
}

function renderHome() {
  const phone = $("#home");
  phone.className = `phone home-phone ${state.theme}`;
  let body;
  if (state.theme === "studio") {
    body = `${appbar}<div class="home-body">
      <div class="home-intro"><h2 class="home-title">Your workspace</h2><p>Record, edit and export on this device.</p></div>
      <section aria-label="Latest edit"><div class="section-head"><h3>Continue editing</h3></div>${hero(projects[0])}</section>
      ${recent(projects.slice(1, 4))}
      </div><footer class="home-dock">${record("New recording")}${pickFile}</footer>`;
  } else if (state.theme === "pocket") {
    body = `${appbar}<div class="home-body">
      <div class="home-intro"><h2 class="home-title">Your projects</h2><p>Pick up where you left off, or record something new.</p></div>
      <section class="feature" aria-label="Latest edit"><div class="section-head"><h3>Continue editing</h3></div>${hero(projects[0], true)}</section>
      ${recent(projects.slice(1, 3))}
      </div><footer class="home-dock" aria-label="Home actions">
      <button type="button" class="dock-item" data-action="home" aria-current="page">${icon("home")}Home</button>
      ${record("Record")}
      <button type="button" class="dock-item" data-action="import">${icon("import")}Import</button>
      </footer>`;
  } else {
    body = `${appbar}<div class="home-body">
      <div class="home-intro">
        <h2 class="home-title">Project library</h2>
        <p>Screen recordings and edits on this device.</p>
        <div class="home-actions" role="group" aria-label="Start a project">${cutRecord}${cutImport}</div>
      </div>
      <section class="library" aria-label="Project library">
        <nav class="library-tabs" aria-label="Library view">
          <button type="button" data-library="projects" aria-pressed="${state.library === "projects"}">Projects</button>
          <button type="button" data-library="recordings" aria-pressed="${state.library === "recordings"}">Recordings</button>
        </nav>
        <label class="library-search">${icon("search")}<input type="search" aria-label="Find in library" placeholder="Find in library"></label>
        <div id="library-items"></div>
      </section></div>`;
  }
  phone.innerHTML = `${systemBars.top}${body}${systemBars.bottom}`;
  if (state.theme === "cut") {
    $("#home input").value = state.query;
    updateLibrary();
  }
}

function waveform() {
  return `<svg viewBox="0 0 345 22" preserveAspectRatio="none" aria-hidden="true">${Array.from({length: 115}, (_, i) => {
    const cadence = Math.abs(Math.sin(i * .12) * Math.sin(i * .071 + .8));
    const height = 2 + cadence * (4 + ((i * 23) % 13));
    return `<path stroke="currentColor" stroke-width="1.5" d="M${i * 3 + 1} ${11 - height / 2}v${height}"/>`;
  }).join("")}</svg>`;
}
function timeline() {
  const project = state.project;
  const ticks = [0, .2, .4, .6, .8].map(fraction => time(project.time * fraction));
  const count = Math.min(state.zooms, 4);
  const sizes = [15, 16, 15, 12];
  const positions = [17, 44, 71, 86];
  return `<section class="timeline" aria-label="Timeline" data-track="${state.tool ?? "screen"}">
    <div class="track-names" aria-hidden="true"><span>Time</span><span>Screen</span><span>Zoom</span><span>Mic</span></div>
    <div class="time-grid" style="--pos: ${100 * state.seconds / project.time}%">
      <div class="ruler" aria-hidden="true">${ticks.map(tick => `<span>${tick}</span>`).join("")}</div>
      <div class="lanes" aria-hidden="true">
        <div class="lane video-lane">${Array.from({ length: 8 }, (_, i) => cover(project, false, i % 4)).join("")}${state.splits.map(second => `<i class="split-mark" style="left:${second / project.time * 100}%"></i>`).join("")}</div>
        <div class="lane zoom-lane">${positions.slice(0, count).map((left, i) => `<span class="zoom-block" style="left:${left}%;width:${sizes[i]}%">${["1.5×", "1.25×", "1.8×", "1.5×"][i]}</span>`).join("")}</div>
        <div class="lane audio-lane">${waveform()}</div>
      </div>
      <div class="playhead" aria-hidden="true"></div>
      <input type="range" aria-label="Scrub timeline" aria-valuetext="${precise(state.seconds)} of ${time(project.time)}" min="0" max="${project.time}" step="0.1" value="${state.seconds}">
    </div></section>`;
}

function toolDetail() {
  if (!state.tool) return "";
  const tool = tools[state.tool];
  let hint = tool.hint;
  if (state.tool === "zoom") hint = `${state.zooms} focus regions · ${hint}`;
  if (state.tool === "trim" && state.theme === "cut") hint = `Screen clip · ${time(state.project.time)}`;
  if (state.tool === "trim" && state.splits.length) hint = `Split at ${precise(state.splits.at(-1))}`;
  return `<section class="tool-detail" aria-label="${tool.label} options">
    <div><h3>${tool.label}</h3><p>${hint}</p></div>
    <button type="button" class="mini-action" data-action="tool-detail">${tool.action}</button></section>`;
}

function renderEditor() {
  const phone = $("#editor");
  phone.className = `phone editor-phone ${state.theme}`;
  const rail = ["trim", "zoom", "frame", "audio", "clips"].map(key =>
    `<button type="button" class="tool" data-tool="${key}" aria-pressed="${key === state.tool}">${icon(key)}${tools[key].label}</button>`).join("");
  phone.innerHTML = `${systemBars.top}
    <header class="appbar editor-bar">
      ${iconButton("back", "Back to Home", "home")}
      <div class="editor-identity"><h2 tabindex="-1">${state.project.name}</h2><p>${state.theme === "cut" ? "Portrait · 9:16" : "Local project"}</p></div>
      <button type="button" class="filled" data-action="export">${icon("down")}Export</button>
    </header>
    <div class="editor-body">
      <div class="preview${state.fit ? " is-fit" : ""}" role="img" aria-label="Illustrated portrait preview of ${state.project.name}">${cover(state.project)}</div>
      <div class="transport" aria-label="Playback controls">
        <div class="transport-time"><strong></strong> / ${time(state.project.time)}</div>
        ${iconButton(state.playing ? "pause" : "play", state.playing ? "Pause preview" : "Play preview", "play")}
        ${iconButton("undo", "Undo last edit", "undo", state.edits.length ? "" : "disabled")}
        ${iconButton("fit", "Fit preview", "fit", `aria-pressed="${state.fit}"`)}
      </div>
      ${timeline()}
      <div class="editor-bottom">${state.theme === "pocket" ? "" : toolDetail()}
        <nav class="toolrail" aria-label="Editing tools">${rail}</nav>
        ${state.theme === "pocket" ? toolDetail() : ""}
      </div>
    </div>${systemBars.bottom}`;
  updatePlayback();
}

function updatePlayback() {
  $("#editor .transport-time strong").textContent = precise(state.seconds);
  const slider = $("#editor input");
  slider.value = state.seconds;
  slider.setAttribute("aria-valuetext", `${precise(state.seconds)} of ${time(state.project.time)}`);
  $("#editor .time-grid").style.setProperty("--pos", `${Math.min(99.6, 100 * state.seconds / state.project.time)}%`);
  const preview = $("#editor .preview .cover");
  preview.dataset.beat = Math.floor(state.seconds / 4.5) % 4;
}

function selectScreen(screen, moveFocus = false) {
  state.screen = screen;
  $(".screens").dataset.screen = screen;
  document.querySelectorAll("[data-screen][aria-pressed]").forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.screen === screen));
  });
  if (moveFocus) (screen === "editor" ? $("#editor h2") : $("#home .home-title")).focus({ preventScroll: true });
}

function selectDirection(theme) {
  stopPlayback();
  state.theme = theme;
  state.tool = { studio: null, pocket: "zoom", cut: "trim" }[theme];
  $("#direction-note").textContent = directions[theme];
  document.querySelectorAll("[data-theme]").forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.theme === theme));
  });
  renderHome();
  renderEditor();
}

function openProject(id) {
  stopPlayback();
  state.project = projects.find(project => project.id === id);
  state.zooms = state.project.zoom;
  state.splits = [];
  state.edits = [];
  state.seconds = Math.min(6.4, state.project.time);
  renderEditor();
  selectScreen("editor", true);
}

let playbackTimer;
function stopPlayback() {
  clearInterval(playbackTimer);
  state.playing = false;
}
function togglePlayback() {
  if (state.playing) {
    stopPlayback();
  } else {
    if (state.seconds >= state.project.time) state.seconds = 0;
    state.playing = true;
    const started = performance.now() - state.seconds * 1000;
    playbackTimer = setInterval(() => {
      state.seconds = Math.min(state.project.time, (performance.now() - started) / 1000);
      updatePlayback();
      if (state.seconds >= state.project.time) {
        stopPlayback();
        updatePlayButton();
      }
    }, 100);
  }
  updatePlayButton();
}
function updatePlayButton() {
  const button = $('#editor [data-action="play"]');
  button.innerHTML = icon(state.playing ? "pause" : "play");
  button.title = state.playing ? "Pause preview" : "Play preview";
  button.setAttribute("aria-label", button.title);
}

const showDemo = (title, copy) => {
  $("#demo-title").textContent = title;
  $("#demo-copy").textContent = copy;
  $("#demo-dialog").showModal();
};

let recordPreviewTimer;
let previewingRecord;
function cancelRecordPreview() {
  clearTimeout(recordPreviewTimer);
  previewingRecord?.removeAttribute("data-preview");
  previewingRecord = null;
}

function showRecordingDemo(button, event) {
  const openPrompt = () => showDemo("New recording", "A real recording would open Android’s screen-sharing prompt. This draft doesn’t request permissions or start capture.");
  const touch = event.pointerType === "touch" || (event.detail > 0 && matchMedia("(hover: none)").matches);
  if (!button.matches(".cut-record-action") || !touch || matchMedia("(prefers-reduced-motion: reduce)").matches) {
    openPrompt();
    return;
  }
  if (previewingRecord) return;
  // Let a normal tap show the full morph before the prototype dialog overlays it.
  previewingRecord = button;
  button.setAttribute("data-preview", "");
  recordPreviewTimer = setTimeout(() => {
    cancelRecordPreview();
    openPrompt();
  }, 300);
}

// Mobile Chromium can wait until release to resolve :active on a scrolling page.
function previewActionPress(event) {
  if (event.pointerType !== "touch" || !event.isPrimary) return;
  event.target.closest(".cut-record-action, .cut-import-action")?.toggleAttribute("data-pressed", event.type === "pointerdown");
}
for (const type of ["pointerdown", "pointerup", "pointercancel", "lostpointercapture"]) {
  document.addEventListener(type, previewActionPress);
}

document.addEventListener("input", (event) => {
  if (event.target.matches("#editor input")) {
    stopPlayback();
    state.seconds = Number(event.target.value);
    updatePlayButton();
    updatePlayback();
  } else if (event.target.matches(".library-search input")) {
    state.query = event.target.value;
    updateLibrary();
  }
});
document.addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button || button.disabled) return;
  if (previewingRecord && button !== previewingRecord) cancelRecordPreview();
  const { theme, screen, project, tool, action, library, id } = button.dataset;
  if (theme) return selectDirection(theme);
  if (screen) return selectScreen(screen);
  if (project) return openProject(project);
  if (tool) {
    state.tool = state.tool === tool && state.theme !== "cut" ? null : tool;
    renderEditor();
    $(`#editor [data-tool="${tool}"]`).focus({ preventScroll: true });
    return;
  }
  if (library) {
    state.library = library;
    renderHome();
    $(`#home [data-library="${library}"]`).focus({ preventScroll: true });
    return;
  }
  switch (action) {
    case "home":
      stopPlayback();
      updatePlayButton();
      $("#home .home-title").tabIndex = -1;
      selectScreen("home", true);
      break;
    case "library":
      state.expanded = !state.expanded;
      renderHome();
      $('#home [data-action="library"]').focus({ preventScroll: true });
      break;
    case "play": togglePlayback(); break;
    case "fit":
      state.fit = !state.fit;
      $("#editor .preview").classList.toggle("is-fit", state.fit);
      button.setAttribute("aria-pressed", String(state.fit));
      break;
    case "undo": {
      const lastEdit = state.edits.pop();
      if (lastEdit === "zoom") state.zooms--;
      if (lastEdit === "trim") state.splits.pop();
      renderEditor();
      $(`#editor [data-action="${state.edits.length ? "undo" : "play"}"]`).focus({ preventScroll: true });
      break;
    }
    case "tool-detail":
      if (state.tool === "trim" && state.seconds > 0 && state.seconds < state.project.time && !state.splits.includes(state.seconds)) {
        state.splits.push(state.seconds);
        state.edits.push("trim");
      } else if (state.tool === "zoom" && state.zooms < 4) {
        state.zooms++;
        state.edits.push("zoom");
      } else {
        return showDemo("Tool preview", `${tools[state.tool].action} is shown here as a concept. This draft does not edit a video file.`);
      }
      renderEditor();
      $('#editor [data-action="tool-detail"]').focus({ preventScroll: true });
      break;
    case "record": showRecordingDemo(button, event); break;
    case "import": showDemo("Import a video", "The app would open Android’s file picker. This draft doesn’t access your device files."); break;
    case "export": showDemo("Export video", "The app would offer video quality settings and Save to device. Nothing is rendered or saved in this draft."); break;
    case "settings": showDemo("Settings", "Settings is a placeholder in this round. The concepts cover Home and Editor."); break;
    case "project-options": showDemo(projects.find(item => item.id === id).name, "A project menu is reserved here for rename, move to folder, and trash."); break;
  }
});

selectDirection(state.theme);
