# Uniform Management Frontend

React/Vite admin UI for the Spring Boot backend and `uniform-ai` integration.

## Requirements

- Node.js 20 or newer
- Backend running at `http://localhost:8080`
- Uniform AI running at `http://127.0.0.1:5001` when evaluations are executed

## Setup

```powershell
cd frontend
copy .env.example .env
npm install
npm run dev
```

Open `http://localhost:5173`.

Default backend admin credentials are documented in `backend/README.md`.

## Build

```powershell
npm run build
```

The UI compresses uploaded images in the browser before sending them to the backend, targets files under 1 MB, and displays saved backend image endpoints through authenticated blob URLs.

## Real-time Camera

Admins can open `/realtime-camera` to use the browser camera with the fast AI overlay endpoint.
Camera access works on `localhost`; deployed domains must use HTTPS. On phones, the page
prefers the rear camera first and includes a switch camera button.

## Uniform Schedules

Admins can open `/uniform-schedules` to configure weekly uniform requirements per actual class and weekday. The page uses the backend class list from student records and saves the full seven-day schedule.
