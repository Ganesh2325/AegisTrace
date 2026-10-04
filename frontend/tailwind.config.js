/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#12161c",
        panel: "#1c232d",
        line: "#313b49",
        mist: "#9aa8ba",
        paper: "#e7edf4",
        amber: "#e0a045",
        moss: "#3cbf8e",
        rose: "#e15d5d",
        sky: "#79b8ff",
      },
      fontFamily: {
        sans: ["Segoe UI", "Helvetica Neue", "sans-serif"],
        mono: ["Cascadia Code", "Consolas", "monospace"],
      },
    },
  },
  plugins: [],
};
