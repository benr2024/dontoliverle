const input = document.getElementById("guessInput");
const btn = document.getElementById("guessBtn");
const suggBox = document.getElementById("suggestions");
const grid = document.getElementById("grid");
const statusEl = document.getElementById("status");
const timerEl = document.getElementById("timer");
const infoBtn = document.getElementById("infoBtn");
const modal = document.getElementById("infoModal");
const overlay = document.getElementById("modalOverlay");
const closeModalBtn = document.getElementById("closeModal");
const shareBtn = document.getElementById("shareBtn");

let nextResetEpochMs = null;
let timerInterval = null;

function startTimer() {
  if (!nextResetEpochMs) return;

  if (timerInterval) clearInterval(timerInterval);

  const tick = () => {
    const diff = Math.max(0, nextResetEpochMs - Date.now());
    const total = Math.floor(diff / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    timerEl.textContent = `Next song in: ${String(h).padStart(2,"0")}:${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}`;
  };

  tick();
  timerInterval = setInterval(tick, 1000);
}

function hintToEmoji(code) {
  if (code === "EQ") return "🟩";
  if (code === "UP" || code === "DOWN") return "🟧";
  if (code === "NO") return "🟥";
  return "⬜";
}

function buildShareText() {
  const guesses = loadGuesses();
  const used = guesses.length;

  let header = `DonToliverdle ${used}/${maxGuesses}\n\n`;

  let lines = guesses.map(g => {
    const h = g.hints;
    return [
      hintToEmoji(h.album),
      hintToEmoji(h.year),
      hintToEmoji(h.trackNumber),
      hintToEmoji(h.duration)
    ].join(" ");
  });

  return header + lines.join("\n");
}

shareBtn.addEventListener("click", async () => {
  const text = buildShareText();
  await navigator.clipboard.writeText(text);
  shareBtn.textContent = "Copied!";
  setTimeout(() => shareBtn.textContent = "Share", 1500);
});

function openInfo() {
  modal.classList.remove("hidden");
  overlay.classList.remove("hidden");
}

function closeInfo() {
  modal.classList.add("hidden");
  overlay.classList.add("hidden");
}

infoBtn.addEventListener("click", openInfo);
closeModalBtn.addEventListener("click", closeInfo);
overlay.addEventListener("click", closeInfo);

let todayDate = null;
let maxGuesses = 8;
let selected = null; // { trackId, name, albumName, releaseYear }

function keyForToday() {
  return `dongame:${todayDate}:guesses`;
}

function msToMinSec(ms) {
  if (ms == null) return "—";
  const s = Math.round(ms / 1000);
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${String(r).padStart(2, "0")}`;
}

function hintBadge(type, display) {
  const div = document.createElement("div");
  div.className = `badge ${type}`;
  div.textContent = display;
  return div;
}

function renderCompareBadge(code, displayValue) {
  if (code === "EQ") return hintBadge("eq", `${displayValue ?? "—"} ✅`);
  if (code === "UP") return hintBadge("up", `${displayValue ?? "—"} ↑`);
  if (code === "DOWN") return hintBadge("down", `${displayValue ?? "—"} ↓`);
  return hintBadge("up", "—");
}

function clearSuggestions() {
  suggBox.style.display = "none";
  suggBox.innerHTML = "";
}

function setStatus(msg) {
  statusEl.textContent = msg || "";
}

function saveGuess(resp) {
  const arr = loadGuesses();
  arr.push(resp);
  localStorage.setItem(keyForToday(), JSON.stringify(arr));
}

function loadGuesses() {
  try {
    const raw = localStorage.getItem(keyForToday());
    if (!raw) return [];
    return JSON.parse(raw);
  } catch {
    return [];
  }
}

async function apiToday() {
  const r = await fetch("/api/today");
  const j = await r.json();
  todayDate = j.date;
  maxGuesses = j.maxGuesses;
  nextResetEpochMs = j.nextResetEpochMs;
  startTimer();
}

async function apiSearch(q) {
  const r = await fetch(`/api/search?q=${encodeURIComponent(q)}`);
  return await r.json();
}

async function apiGuess(trackId) {
  const r = await fetch("/api/guess", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ trackId })
  });

  if (!r.ok) {
    const txt = await r.text();
    throw new Error(txt || `HTTP ${r.status}`);
  }
  return await r.json();
}

async function apiAnswer() {
  const r = await fetch("/api/answer");
  return await r.json();
}

async function revealAnswer(prefix) {
  try {
    const a = await apiAnswer();
    setStatus(`${prefix} Today’s song was: ${a.name} — ${a.albumName} (${a.releaseYear ?? "—"})`);
  } catch {
    setStatus(`${prefix} (Couldn’t load answer)`);
  }
}

function renderRow(resp) {
  const row = document.createElement("div");
  row.className = "row";

  const guess = resp.guess;
  const hints = resp.hints;

  const songCell = document.createElement("div");
  songCell.innerHTML = `<div class="cellTitle">${guess.name}</div><div class="meta">${guess.albumName ?? ""}</div>`;

  const albumCell = document.createElement("div");
  albumCell.className = "albumCell";

  const img = document.createElement("img");
  img.className = "albumThumb";
  img.src = guess.imageUrl300 || "";
  img.alt = "";
  img.loading = "lazy";

  const badge = hintBadge(hints.album === "EQ" ? "eq" : "no", hints.album === "EQ" ? "✅" : "❌");

  albumCell.appendChild(img);
  albumCell.appendChild(badge);

  const yearCell = document.createElement("div");
  yearCell.appendChild(renderCompareBadge(hints.year, guess.releaseYear));

  const numCell = document.createElement("div");
  numCell.appendChild(renderCompareBadge(hints.trackNumber, guess.trackNumber));

  const lenCell = document.createElement("div");
  lenCell.appendChild(renderCompareBadge(hints.duration, msToMinSec(guess.durationMs)));

  // Features placeholder for now (we’ll wire Spotify later)
  const featCell = document.createElement("div");
  featCell.appendChild(hintBadge("up", "—"));

  row.appendChild(songCell);
  row.appendChild(albumCell);
  row.appendChild(yearCell);
  row.appendChild(numCell);
  row.appendChild(lenCell);
  row.appendChild(featCell);

  grid.prepend(row);
}

async function restoreGuesses() {
  const arr = loadGuesses();
  grid.innerHTML = "";
  for (const g of arr) renderRow(g);

  const used = arr.length;

  if (arr.some(x => x.correct)) {
    input.disabled = true;
    btn.disabled = true;
    await revealAnswer(`You got it! 🎉 (${used}/${maxGuesses})`);
    return;
  }

  if (used >= maxGuesses) {
    input.disabled = true;
    btn.disabled = true;
    await revealAnswer(`Out of guesses 😭 (${used}/${maxGuesses})`);
    return;
  }

  setStatus(`${used}/${maxGuesses} guesses used`);
}

let searchTimer = null;

input.addEventListener("input", () => {
  const q = input.value.trim();
  selected = null;

  if (searchTimer) clearTimeout(searchTimer);

  if (q.length < 2) {
    clearSuggestions();
    return;
  }

  searchTimer = setTimeout(async () => {
    const results = await apiSearch(q);
    if (!results || results.length === 0) {
      clearSuggestions();
      return;
    }

    suggBox.innerHTML = "";
    for (const s of results) {
      const div = document.createElement("div");
      div.className = "suggestion";
	  div.innerHTML = `
	    <div class="sugRow">
	      <img class="sugImg" src="${s.imageUrl300 ?? ""}" alt="" />
	      <div>
	        <div class="sugTitle">${s.name}</div>
	        <div class="meta">${s.albumName ?? ""}</div>
	      </div>
	    </div>
	  `;
      div.addEventListener("click", () => {
        selected = s;
        input.value = s.name;
        clearSuggestions();
        setStatus(`Selected: ${s.name}`);
      });
      suggBox.appendChild(div);
    }
    suggBox.style.display = "block";
  }, 200);
});

input.addEventListener("keydown", async (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    await submitGuess();
  }
});

btn.addEventListener("click", submitGuess);

async function submitGuess() {
  const used = loadGuesses().length;
  if (used >= maxGuesses) {
    await revealAnswer(`Out of guesses 😭 (${used}/${maxGuesses})`);
    input.disabled = true;
    btn.disabled = true;
    return;
  }

  if (!selected || !selected.trackId) {
    setStatus("Pick a song from the dropdown suggestions.");
    return;
  }

  try {
    btn.disabled = true;

    const resp = await apiGuess(selected.trackId);
    saveGuess(resp);
    renderRow(resp);

    const nowUsed = loadGuesses().length;

    input.value = "";
    selected = null;

    if (resp.correct) {
      input.disabled = true;
      btn.disabled = true;
      await revealAnswer(`You got it! 🎉 (${nowUsed}/${maxGuesses})`);
	  shareBtn.classList.remove("hidden");
      return;
    }

    if (nowUsed >= maxGuesses) {
      input.disabled = true;
      btn.disabled = true;
      await revealAnswer(`Out of guesses 😭 (${nowUsed}/${maxGuesses})`);
	  shareBtn.classList.remove("hidden");
      return;
    }

    setStatus(`${nowUsed}/${maxGuesses} guesses used`);
    btn.disabled = false;

  } catch (err) {
    setStatus(`Error: ${err.message}`);
    btn.disabled = false;
  }
}

(async function init() {
  await apiToday();
  await restoreGuesses();
})();