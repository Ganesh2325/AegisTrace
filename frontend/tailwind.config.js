/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        canvas: "rgb(var(--canvas) / <alpha-value>)",
        surface: "rgb(var(--surface) / <alpha-value>)",
        elevated: "rgb(var(--elevated) / <alpha-value>)",
        overlay: "rgb(var(--overlay) / <alpha-value>)",
        line: "rgb(var(--line) / <alpha-value>)",
        "line-strong": "rgb(var(--line-strong) / <alpha-value>)",
        paper: "rgb(var(--paper) / <alpha-value>)",
        muted: "rgb(var(--muted) / <alpha-value>)",
        faint: "rgb(var(--faint) / <alpha-value>)",
        accent: "rgb(var(--accent) / <alpha-value>)",
        success: "rgb(var(--success) / <alpha-value>)",
        warning: "rgb(var(--warning) / <alpha-value>)",
        danger: "rgb(var(--danger) / <alpha-value>)",
        info: "rgb(var(--info) / <alpha-value>)",
        ink: "rgb(var(--canvas) / <alpha-value>)",
        panel: "rgb(var(--elevated) / <alpha-value>)",
        mist: "rgb(var(--muted) / <alpha-value>)",
        amber: "rgb(var(--accent) / <alpha-value>)",
        moss: "rgb(var(--success) / <alpha-value>)",
        rose: "rgb(var(--danger) / <alpha-value>)",
        sky: "rgb(var(--info) / <alpha-value>)",
      },
      fontFamily: {
        sans: ["Segoe UI", "Helvetica Neue", "sans-serif"],
        mono: ["Cascadia Code", "Consolas", "monospace"],
      },
      boxShadow: {
        panel: "0 8px 24px rgba(0, 0, 0, 0.28)",
      },
      borderRadius: {
        sm: "4px",
        md: "6px",
        lg: "10px",
      },
    },
  },
  plugins: [],
};
