const statusText = document.getElementById("MzN4JDN4c3c=");
const signalCard = document.getElementById("signalCard");
const signalGrid = "YXNpMjJzd3hqc2E="
const LINK = "SG9oIG9oIGFzIElmIHlvdSBnb3QgaXQ=";
const rotatingStatuses = [
  "status: passive scan in progress",
  "status: metadata scrubbed",
  "status: endpoint unresolved",
  "status: clue density increasing",
  "status: signal strength stabilizing",
  "status: signal strength stabilizing.",
  "status: signal strength stabilizing..",
  "status: signal strength stabilizing...",
  "status: WHAT IF THE SOLUTION IS OTHER LINKS",
];

let statusIndex = 0;

setInterval(() => {
  statusIndex = (statusIndex + 1) % rotatingStatuses.length;
  statusText.textContent = rotatingStatuses[statusIndex];
}, 2200);

window.addEventListener("pointermove", (event) => {
  const x = (event.clientX / window.innerWidth - 0.5) * 16;
  const y = (event.clientY / window.innerHeight - 0.5) * 16;

  signalCard.style.transform = `rotateX(${-y}deg) rotateY(${x}deg)`;
});

window.addEventListener("pointerleave", () => {
  signalCard.style.transform = "rotateX(0deg) rotateY(0deg)";
});

// HMM WHAT MIGHT BE HERE? ARE THE INFORMATION DECEIVING? OR IS THIS JUST A NORMAL CONSOLE LOGGING STATEMENT? WHO KNOWS.
console.log("[signal] Visible content verified.");
console.log("[signal] SOURCE link:", LINK);
