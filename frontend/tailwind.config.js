/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.scala',
  ],
  darkMode: 'class',
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
