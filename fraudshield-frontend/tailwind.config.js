/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: {
    extend: {
      colors: {
        "risk-high":   "#ef4444",
        "risk-medium": "#f97316",
        "risk-low":    "#eab308",
        "risk-normal": "#22c55e",
        "dark-bg":     "#0f172a",
        "dark-card":   "#1e293b",
        "dark-border": "#334155",
      },
    },
  },
  plugins: [],
};
