const themeButton = document.querySelector("#theme");
const themeRoot = document.documentElement;

function updateThemeLabel() {
  themeButton.textContent = themeRoot.dataset.theme === "night" ? "Switch to Day" : "Switch to Night";
}
updateThemeLabel();
themeButton.addEventListener("click", () => {
  themeRoot.dataset.theme = themeRoot.dataset.theme === "night" ? "day" : "night";
  updateThemeLabel();
  try { localStorage.setItem("mangocare-theme", themeRoot.dataset.theme); } catch {}
});
