const STORAGE_KEY = "flemingo-progress-v1";

const state = {
  activities: [],
  logs: {},
  selectedActivityId: null,
  selectedRange: "30",
};

const ui = {
  activityForm: document.getElementById("activity-form"),
  activityName: document.getElementById("activity-name"),
  activityUnit: document.getElementById("activity-unit"),
  activityTarget: document.getElementById("activity-target"),
  activityStartDate: document.getElementById("activity-start-date"),
  activityColor: document.getElementById("activity-color"),
  activityList: document.getElementById("activity-list"),
  activityEmpty: document.getElementById("activity-empty"),
  checkinDate: document.getElementById("checkin-date"),
  checkinBody: document.getElementById("checkin-body"),
  checkinEmpty: document.getElementById("checkin-empty"),
  saveAllBtn: document.getElementById("save-all-btn"),
  activitySelect: document.getElementById("activity-select"),
  rangeButtons: document.querySelectorAll(".range-btn"),
  metrics: document.getElementById("metrics"),
  insight: document.getElementById("insight"),
  projectionToggle: document.getElementById("projection-toggle"),
  exportBtn: document.getElementById("export-btn"),
  importFile: document.getElementById("import-file"),
};

const charts = {
  daily: null,
  cumulative: null,
};

init();

function init() {
  const today = getToday();
  ui.checkinDate.value = today;
  ui.activityStartDate.value = today;

  loadState();
  bindEvents();
  renderAll();
}

function bindEvents() {
  ui.activityForm.addEventListener("submit", handleCreateActivity);
  ui.checkinDate.addEventListener("change", renderCheckinTable);
  ui.saveAllBtn.addEventListener("click", handleSaveAllEntries);

  ui.activitySelect.addEventListener("change", (event) => {
    state.selectedActivityId = event.target.value || null;
    persistState();
    renderAnalytics();
  });

  ui.rangeButtons.forEach((button) => {
    button.addEventListener("click", () => {
      state.selectedRange = button.dataset.range;
      ui.rangeButtons.forEach((b) => b.classList.remove("active"));
      button.classList.add("active");
      renderAnalytics();
    });
  });

  ui.projectionToggle.addEventListener("change", () => {
    renderAnalytics();
  });

  ui.exportBtn.addEventListener("click", exportData);
  ui.importFile.addEventListener("change", importData);
}

function handleCreateActivity(event) {
  event.preventDefault();

  const name = ui.activityName.value.trim();
  const unit = ui.activityUnit.value.trim();
  const startDate = ui.activityStartDate.value;
  const color = ui.activityColor.value;
  const targetValue = ui.activityTarget.value.trim();

  if (!name || !unit || !startDate) {
    window.alert("Please fill in activity name, unit, and start date.");
    return;
  }

  const activity = {
    id: crypto.randomUUID(),
    name,
    unit,
    startDate,
    target: targetValue ? Number(targetValue) : null,
    color,
    createdAt: new Date().toISOString(),
  };

  state.activities.push(activity);
  state.logs[activity.id] = state.logs[activity.id] || {};

  if (!state.selectedActivityId) {
    state.selectedActivityId = activity.id;
  }

  ui.activityForm.reset();
  ui.activityColor.value = "#f97316";
  ui.activityStartDate.value = getToday();

  persistState();
  renderAll();
}

function handleEditActivity(activityId) {
  const activity = state.activities.find((item) => item.id === activityId);
  if (!activity) return;

  const newName = window.prompt("Edit activity name:", activity.name);
  if (newName === null) return;

  const newUnit = window.prompt("Edit unit:", activity.unit);
  if (newUnit === null) return;

  const newTarget = window.prompt(
    "Edit daily target (leave empty for none):",
    activity.target == null ? "" : String(activity.target)
  );
  if (newTarget === null) return;

  const cleanedName = newName.trim();
  const cleanedUnit = newUnit.trim();
  if (!cleanedName || !cleanedUnit) {
    window.alert("Activity name and unit cannot be empty.");
    return;
  }

  activity.name = cleanedName;
  activity.unit = cleanedUnit;
  activity.target = newTarget.trim() === "" ? null : Number(newTarget);

  persistState();
  renderAll();
}

function handleDeleteActivity(activityId) {
  const activity = state.activities.find((item) => item.id === activityId);
  if (!activity) return;

  const confirmed = window.confirm(
    `Delete \"${activity.name}\" and all its logs? This cannot be undone.`
  );

  if (!confirmed) return;

  state.activities = state.activities.filter((item) => item.id !== activityId);
  delete state.logs[activityId];

  if (state.selectedActivityId === activityId) {
    state.selectedActivityId = state.activities[0]?.id || null;
  }

  persistState();
  renderAll();
}

function handleSaveSingleEntry(activityId, date, value) {
  const parsedValue = Number(value);

  if (Number.isNaN(parsedValue) || parsedValue < 0) {
    window.alert("Please enter a valid non-negative number.");
    return;
  }

  state.logs[activityId] = state.logs[activityId] || {};
  state.logs[activityId][date] = roundTo(parsedValue, 2);

  persistState();
  renderAnalytics();
}

function handleSaveAllEntries() {
  const date = ui.checkinDate.value;
  if (!date) {
    window.alert("Please choose a check-in date first.");
    return;
  }

  const inputs = ui.checkinBody.querySelectorAll("input[data-activity-id]");

  inputs.forEach((input) => {
    const activityId = input.dataset.activityId;
    const raw = input.value.trim();
    if (raw === "") return;

    const parsed = Number(raw);
    if (Number.isNaN(parsed) || parsed < 0) return;

    state.logs[activityId] = state.logs[activityId] || {};
    state.logs[activityId][date] = roundTo(parsed, 2);
  });

  persistState();
  renderAll();
}

function renderAll() {
  renderActivityList();
  renderCheckinTable();
  renderActivitySelect();
  renderAnalytics();
}

function renderActivityList() {
  ui.activityList.innerHTML = "";

  if (state.activities.length === 0) {
    ui.activityEmpty.style.display = "block";
    return;
  }

  ui.activityEmpty.style.display = "none";

  state.activities.forEach((activity) => {
    const li = document.createElement("li");
    li.className = "activity-item";

    const total = getTotalForActivity(activity.id);
    const currentStreak = getStreaks(activity.id).current;

    li.innerHTML = `
      <div class="activity-main">
        <span class="color-dot" style="background:${activity.color}"></span>
        <div>
          <strong>${escapeHtml(activity.name)}</strong>
          <div class="activity-meta">
            Unit: ${escapeHtml(activity.unit)} | Target: ${formatTarget(activity)} | Total: ${formatNumber(total)} ${escapeHtml(activity.unit)} | Streak: ${currentStreak}d
          </div>
        </div>
      </div>
      <div class="row-actions">
        <button type="button" class="btn btn-small" data-action="edit">Edit</button>
        <button type="button" class="btn btn-small btn-danger" data-action="delete">Delete</button>
      </div>
    `;

    li.querySelector('[data-action="edit"]').addEventListener("click", () => {
      handleEditActivity(activity.id);
    });

    li.querySelector('[data-action="delete"]').addEventListener("click", () => {
      handleDeleteActivity(activity.id);
    });

    ui.activityList.appendChild(li);
  });
}

function renderCheckinTable() {
  ui.checkinBody.innerHTML = "";

  const hasActivities = state.activities.length > 0;
  ui.checkinEmpty.style.display = hasActivities ? "none" : "block";
  ui.saveAllBtn.disabled = !hasActivities;

  if (!hasActivities) return;

  const date = ui.checkinDate.value || getToday();

  state.activities.forEach((activity) => {
    const tr = document.createElement("tr");
    const existingValue = state.logs[activity.id]?.[date];

    tr.innerHTML = `
      <td><span class="color-dot" style="background:${activity.color}"></span> ${escapeHtml(activity.name)} (${escapeHtml(activity.unit)})</td>
      <td>${formatTarget(activity)}</td>
      <td>
        <input
          type="number"
          min="0"
          step="0.01"
          data-activity-id="${activity.id}"
          value="${existingValue ?? ""}"
          placeholder="Enter result"
        >
      </td>
      <td>
        <button type="button" class="btn btn-small" data-save="${activity.id}">Save</button>
      </td>
    `;

    const saveButton = tr.querySelector(`[data-save="${activity.id}"]`);
    const input = tr.querySelector("input");

    saveButton.addEventListener("click", () => {
      handleSaveSingleEntry(activity.id, date, input.value);
    });

    ui.checkinBody.appendChild(tr);
  });
}

function renderActivitySelect() {
  const prevSelected = state.selectedActivityId;
  ui.activitySelect.innerHTML = "";

  if (state.activities.length === 0) {
    const emptyOption = document.createElement("option");
    emptyOption.textContent = "No activities";
    emptyOption.value = "";
    ui.activitySelect.appendChild(emptyOption);
    state.selectedActivityId = null;
    return;
  }

  state.activities.forEach((activity) => {
    const option = document.createElement("option");
    option.value = activity.id;
    option.textContent = `${activity.name} (${activity.unit})`;
    ui.activitySelect.appendChild(option);
  });

  const chosen = state.activities.some((a) => a.id === prevSelected)
    ? prevSelected
    : state.activities[0].id;

  state.selectedActivityId = chosen;
  ui.activitySelect.value = chosen;
}

function renderAnalytics() {
  const activity = state.activities.find((item) => item.id === state.selectedActivityId);
  if (!activity) {
    ui.metrics.innerHTML = "";
    ui.insight.textContent = "Create and select an activity to view compounding analytics.";
    destroyCharts();
    return;
  }

  const { labels, values, allDates } = getActivitySeries(activity.id, state.selectedRange, activity.startDate);
  const movingAvg = movingAverage(values, 7);
  const cumulative = cumulativeValues(values);

  const total = cumulative[cumulative.length - 1] || 0;
  const streaks = getStreaks(activity.id);
  const sevenDayAvg = average(values.slice(-7));
  const periodDelta = calculatePeriodDelta(activity.id, state.selectedRange, activity.startDate);

  const metrics = [
    { label: "Current Streak", value: `${streaks.current}d` },
    { label: "Best Streak", value: `${streaks.best}d` },
    { label: "7-Day Avg", value: `${formatNumber(sevenDayAvg)} ${activity.unit}` },
    { label: "Total", value: `${formatNumber(total)} ${activity.unit}` },
    { label: "Period Change", value: formatPercent(periodDelta) },
  ];

  ui.metrics.innerHTML = metrics
    .map(
      (item) => `
      <article class="metric-card">
        <div class="label">${item.label}</div>
        <div class="value">${item.value}</div>
      </article>
    `
    )
    .join("");

  renderDailyChart(labels, values, movingAvg, activity.color);
  renderCumulativeChart(labels, cumulative, values, activity.color, ui.projectionToggle.checked);

  const monthlyProjection = projectFutureCumulative(total, average(values), 30, 0.01);
  const allTimeTotal = sumByDates(state.logs[activity.id] || {}, allDates);

  ui.insight.innerHTML = `
    <strong>Insight:</strong> In this ${state.selectedRange.toUpperCase()} view, you accumulated
    <strong>${formatNumber(total)} ${escapeHtml(activity.unit)}</strong>.
    All-time total is <strong>${formatNumber(allTimeTotal)} ${escapeHtml(activity.unit)}</strong>.
    If your current average improves by 1% each day, you could add approximately
    <strong>${formatNumber(monthlyProjection.delta)} ${escapeHtml(activity.unit)}</strong> over the next 30 days.
  `;
}

function renderDailyChart(labels, values, movingAvg, color) {
  const ctx = document.getElementById("daily-chart");
  if (!ctx) return;

  if (charts.daily) charts.daily.destroy();

  charts.daily = new Chart(ctx, {
    type: "line",
    data: {
      labels,
      datasets: [
        {
          label: "Daily Result",
          data: values,
          borderColor: color,
          backgroundColor: hexToRgba(color, 0.2),
          borderWidth: 2.4,
          fill: true,
          tension: 0.24,
          pointRadius: 2,
        },
        {
          label: "7-Day Moving Avg",
          data: movingAvg,
          borderColor: "#2a9d8f",
          backgroundColor: "transparent",
          borderDash: [6, 5],
          borderWidth: 2,
          fill: false,
          tension: 0.3,
          pointRadius: 0,
        },
      ],
    },
    options: chartOptions(`${color}`),
  });
}

function renderCumulativeChart(labels, cumulative, values, color, showProjection) {
  const ctx = document.getElementById("cumulative-chart");
  if (!ctx) return;

  if (charts.cumulative) charts.cumulative.destroy();

  const datasets = [
    {
      label: "Cumulative Total",
      data: cumulative,
      borderColor: "#264653",
      backgroundColor: hexToRgba("#264653", 0.15),
      borderWidth: 2.8,
      fill: true,
      tension: 0.22,
      pointRadius: 1.8,
    },
  ];

  if (showProjection) {
    const projection = projectCurve(cumulative[cumulative.length - 1] || 0, average(values), labels.length, 0.01);
    datasets.push({
      label: "1% Better Projection",
      data: projection,
      borderColor: color,
      backgroundColor: "transparent",
      borderWidth: 2,
      borderDash: [5, 4],
      fill: false,
      tension: 0.24,
      pointRadius: 0,
    });
  }

  charts.cumulative = new Chart(ctx, {
    type: "line",
    data: {
      labels,
      datasets,
    },
    options: chartOptions("#264653"),
  });
}

function chartOptions(primaryColor) {
  return {
    responsive: true,
    maintainAspectRatio: true,
    aspectRatio: 2,
    interaction: {
      mode: "index",
      intersect: false,
    },
    plugins: {
      legend: {
        labels: {
          usePointStyle: true,
          boxWidth: 7,
          color: "#493c2f",
        },
      },
      tooltip: {
        backgroundColor: "rgba(36, 28, 19, 0.92)",
        titleColor: "#fff3de",
        bodyColor: "#fff3de",
        borderColor: "rgba(255, 240, 210, 0.25)",
        borderWidth: 1,
      },
    },
    scales: {
      x: {
        ticks: {
          color: "#665847",
          maxTicksLimit: 10,
        },
        grid: {
          color: "rgba(68, 52, 33, 0.08)",
        },
      },
      y: {
        beginAtZero: true,
        ticks: {
          color: "#665847",
        },
        grid: {
          color: "rgba(68, 52, 33, 0.08)",
        },
      },
    },
    elements: {
      line: {
        borderColor: primaryColor,
      },
    },
  };
}

function getActivitySeries(activityId, range, startDate) {
  const logMap = state.logs[activityId] || {};
  const today = getToday();

  let rangeStart;
  if (range === "all") {
    rangeStart = startDate;
  } else {
    const days = Number(range) || 30;
    rangeStart = addDays(today, -(days - 1));
    if (rangeStart < startDate) {
      rangeStart = startDate;
    }
  }

  const labels = [];
  const values = [];
  const allDates = Object.keys(logMap).sort();

  let current = rangeStart;
  while (current <= today) {
    labels.push(formatShortDate(current));
    values.push(Number(logMap[current] || 0));
    current = addDays(current, 1);
  }

  return { labels, values, allDates };
}

function getStreaks(activityId) {
  const logMap = state.logs[activityId] || {};
  const allDates = Object.keys(logMap).sort();

  if (allDates.length === 0) {
    return { current: 0, best: 0 };
  }

  const today = getToday();
  let current = 0;
  let cursor = today;

  while (Number(logMap[cursor] || 0) > 0) {
    current += 1;
    cursor = addDays(cursor, -1);
  }

  let best = 0;
  let streak = 0;
  const ordered = getOrderedDates(allDates[0], allDates[allDates.length - 1]);

  ordered.forEach((date) => {
    if (Number(logMap[date] || 0) > 0) {
      streak += 1;
      best = Math.max(best, streak);
    } else {
      streak = 0;
    }
  });

  return { current, best };
}

function calculatePeriodDelta(activityId, range, startDate) {
  if (range === "all") return null;

  const days = Number(range) || 30;
  const logMap = state.logs[activityId] || {};
  const today = getToday();

  let currentStart = addDays(today, -(days - 1));
  if (currentStart < startDate) {
    currentStart = startDate;
  }

  const previousEnd = addDays(currentStart, -1);
  const previousStart = addDays(previousEnd, -(days - 1));

  const currentTotal = sumByRange(logMap, currentStart, today);
  const previousTotal = sumByRange(logMap, previousStart, previousEnd);

  if (previousTotal === 0 && currentTotal === 0) return 0;
  if (previousTotal === 0) return null;

  return ((currentTotal - previousTotal) / previousTotal) * 100;
}

function getTotalForActivity(activityId) {
  const logMap = state.logs[activityId] || {};
  return Object.values(logMap).reduce((acc, value) => acc + Number(value || 0), 0);
}

function loadState() {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return;

  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed.activities)) {
      state.activities = parsed.activities;
    }
    if (parsed.logs && typeof parsed.logs === "object") {
      state.logs = parsed.logs;
    }
    if (typeof parsed.selectedRange === "string") {
      state.selectedRange = parsed.selectedRange;
    }
    if (typeof parsed.selectedActivityId === "string") {
      state.selectedActivityId = parsed.selectedActivityId;
    }

    const selectedButton = [...ui.rangeButtons].find((b) => b.dataset.range === state.selectedRange);
    if (selectedButton) {
      ui.rangeButtons.forEach((b) => b.classList.remove("active"));
      selectedButton.classList.add("active");
    }
  } catch (error) {
    console.error("Unable to parse local storage data", error);
  }
}

function persistState() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      activities: state.activities,
      logs: state.logs,
      selectedActivityId: state.selectedActivityId,
      selectedRange: state.selectedRange,
    })
  );
}

function exportData() {
  const payload = {
    exportedAt: new Date().toISOString(),
    version: 1,
    data: {
      activities: state.activities,
      logs: state.logs,
    },
  };

  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `flemingo-export-${getToday()}.json`;
  link.click();
  URL.revokeObjectURL(url);
}

function importData(event) {
  const [file] = event.target.files || [];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = () => {
    try {
      const payload = JSON.parse(String(reader.result || "{}"));
      const imported = payload.data || payload;

      if (!Array.isArray(imported.activities) || typeof imported.logs !== "object") {
        window.alert("Invalid import file format.");
        return;
      }

      state.activities = imported.activities;
      state.logs = imported.logs;
      state.selectedActivityId = state.activities[0]?.id || null;

      persistState();
      renderAll();
    } catch (error) {
      window.alert("Unable to import this file.");
      console.error(error);
    } finally {
      ui.importFile.value = "";
    }
  };

  reader.readAsText(file);
}

function destroyCharts() {
  if (charts.daily) {
    charts.daily.destroy();
    charts.daily = null;
  }
  if (charts.cumulative) {
    charts.cumulative.destroy();
    charts.cumulative = null;
  }
}

function getOrderedDates(start, end) {
  const dates = [];
  let cursor = start;
  while (cursor <= end) {
    dates.push(cursor);
    cursor = addDays(cursor, 1);
  }
  return dates;
}

function sumByRange(logMap, startDate, endDate) {
  if (endDate < startDate) return 0;

  let total = 0;
  let cursor = startDate;
  while (cursor <= endDate) {
    total += Number(logMap[cursor] || 0);
    cursor = addDays(cursor, 1);
  }
  return total;
}

function sumByDates(logMap, dates) {
  return dates.reduce((acc, date) => acc + Number(logMap[date] || 0), 0);
}

function cumulativeValues(values) {
  let running = 0;
  return values.map((value) => {
    running += Number(value || 0);
    return roundTo(running, 2);
  });
}

function movingAverage(values, windowSize) {
  return values.map((_, index) => {
    const start = Math.max(0, index - windowSize + 1);
    const slice = values.slice(start, index + 1);
    return roundTo(average(slice), 2);
  });
}

function average(values) {
  if (!values.length) return 0;
  return values.reduce((acc, value) => acc + Number(value || 0), 0) / values.length;
}

function projectCurve(baseTotal, baseDailyAverage, points, growthRate) {
  let running = Number(baseTotal || 0);
  const daily = Math.max(Number(baseDailyAverage || 0), 0.1);
  const projected = [];

  for (let i = 0; i < points; i += 1) {
    const gained = daily * (1 + growthRate) ** i;
    running += gained;
    projected.push(roundTo(running, 2));
  }

  return projected;
}

function projectFutureCumulative(currentTotal, baseDailyAverage, days, growthRate) {
  const curve = projectCurve(currentTotal, baseDailyAverage, days, growthRate);
  const projectedEnd = curve[curve.length - 1] || currentTotal;
  return {
    projectedEnd,
    delta: projectedEnd - currentTotal,
  };
}

function formatTarget(activity) {
  return activity.target == null ? "None" : `${formatNumber(activity.target)} ${activity.unit}`;
}

function formatPercent(value) {
  if (value == null) return "N/A";
  const sign = value > 0 ? "+" : "";
  return `${sign}${value.toFixed(1)}%`;
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
}

function getToday() {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

function addDays(dateString, amount) {
  const parsed = parseDateParts(dateString);
  if (!parsed) return getToday();

  const date = new Date(Date.UTC(parsed.year, parsed.month - 1, parsed.day));
  date.setUTCDate(date.getUTCDate() + amount);
  return date.toISOString().slice(0, 10);
}

function formatShortDate(dateString) {
  const parsed = parseDateParts(dateString);
  if (!parsed) return dateString;

  const date = new Date(parsed.year, parsed.month - 1, parsed.day);
  return date.toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
  });
}

function parseDateParts(dateString) {
  if (typeof dateString !== "string") return null;

  const [year, month, day] = dateString.split("-").map(Number);
  if (!year || !month || !day) return null;

  return { year, month, day };
}

function roundTo(value, digits) {
  const factor = 10 ** digits;
  return Math.round(Number(value || 0) * factor) / factor;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function hexToRgba(hex, alpha) {
  const normalized = hex.replace("#", "");
  const bigint = parseInt(normalized, 16);
  const r = (bigint >> 16) & 255;
  const g = (bigint >> 8) & 255;
  const b = bigint & 255;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
