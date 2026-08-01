/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        brand: {
          primary: "var(--brand-primary)",
          "primary-foreground": "var(--brand-primary-foreground)",
          secondary: "var(--brand-secondary)",
          "secondary-foreground": "var(--brand-secondary-foreground)",
          accent: "var(--brand-accent)",
          "accent-foreground": "var(--brand-accent-foreground)",
          lavender: "var(--brand-lavender)",
        },
        // Direct brand palette, usable as bg-indigo-500 style utilities too.
        indigo: {
          DEFAULT: "#2A1C58",
          50: "#F1EEFA",
          100: "#E1DAF3",
          200: "#C3B5E7",
          300: "#A590DA",
          400: "#7D64BE",
          500: "#3A2774",
          600: "#2A1C58",
          700: "#221749",
          800: "#19113A",
          900: "#110B2A",
        },
        gold: {
          DEFAULT: "#F4C300",
          50: "#FEFAE6",
          100: "#FDF3BF",
          200: "#FBE780",
          300: "#F9DB40",
          400: "#F4C300",
          500: "#DCAF00",
          600: "#B08C00",
          700: "#846900",
          800: "#584600",
          900: "#2C2300",
        },
        lavender: {
          DEFAULT: "#EFEAF8",
          50: "#FBFAFD",
          100: "#EFEAF8",
          200: "#DCD2F0",
          300: "#C8BAE7",
          400: "#B5A2DF",
          500: "#A18AD7",
        },
        purple: {
          DEFAULT: "#3A2774",
        },
        border: "var(--border)",
        input: "var(--input)",
        ring: "var(--ring)",
        background: "var(--background)",
        foreground: "var(--foreground)",
        card: {
          DEFAULT: "var(--card)",
          foreground: "var(--card-foreground)",
        },
        muted: {
          DEFAULT: "var(--muted)",
          foreground: "var(--muted-foreground)",
        },
        destructive: {
          DEFAULT: "var(--destructive)",
          foreground: "var(--destructive-foreground)",
        },
        success: {
          DEFAULT: "var(--success)",
          foreground: "var(--success-foreground)",
        },
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      fontFamily: {
        sans: [
          "Inter",
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "Segoe UI",
          "Roboto",
          "sans-serif",
        ],
      },
    },
  },
  plugins: [],
};
