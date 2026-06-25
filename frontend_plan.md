# IoT Smart Home Monitor — Frontend Dashboard Implementation Plan

> **Created:** 2026-06-24 | **Framework:** React 18 + Vite + TypeScript | **Location:** `dashboard/` at project root (outside Maven multi-module)

---

## 1. Overview

**What:** A real-time IoT monitoring dashboard consuming the existing backend REST APIs (simulator :8081, gateway :8082, processing :8083) to visualize sensor data, system health, alerts, and simulator controls.

**Goals:**
- **Phase 1:** Fully functional live monitoring dashboard with polling (every 5s) — works with current backend, no changes needed
- **Phase 2:** Historical analytics + device management UI (when persistence-service & device-registry exist)
- **Phase 3:** Login/auth with JWT (when IAM service exists)
- **Phase 4:** Containerized, deployed via Helm

**Scope:** Phase 1 is self-contained. Phases 2–4 depend on corresponding backend phases. Desktop-first, responsive to tablet.

---

## 2. Technology Stack

| Category | Choice | Why |
|----------|--------|-----|
| Build tool | **Vite 6.x** | Fast HMR, native ESM, simple config |
| UI library | **React 18+** | Component model, huge ecosystem |
| Language | **TypeScript 5.x** | Type safety, catches contract drift early |
| Routing | **React Router v7** | Nested layouts, `<Outlet />` pattern |
| Server state | **TanStack Query v5** | Auto-refetch, caching, polling built-in |
| HTTP client | **Axios 1.x** | Interceptors for auth (Phase 3) |
| Styling | **Tailwind CSS 4.x** | Utility-first, fast prototyping, tiny bundle |
| Charts | **Recharts 2.x** | React-native charts, good for time-series |
| Icons | **Lucide React** | Lightweight, tree-shakeable icon set |
| Testing | **Vitest + React Testing Library + MSW** | Vite-native runner, network-level mocking |

---

## 3. Directory Structure

```
dashboard/
├── index.html
├── package.json
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json
├── vite.config.ts                  # Dev proxy config
├── eslint.config.js
├── public/favicon.svg
└── src/
    ├── main.tsx                    # Entry: QueryClientProvider + RouterProvider
    ├── App.tsx                     # Layout: sidebar + header + <Outlet />
    ├── index.css                   # Tailwind directives (@import "tailwindcss")
    ├── api/                        # ── HTTP layer ──
    │   ├── axios.ts                # Configured instance with interceptors
    │   ├── simulator.api.ts        # getStatus, trigger, start, stop
    │   ├── gateway.api.ts          # getHealth, getStats
    │   └── analytics.api.ts        # getStatistics, getSensorStats, getAlerts, getSummary
    ├── hooks/                      # ── TanStack Query hooks ──
    │   ├── useSimulator.ts         # useSimulatorStatus, useSimulatorTrigger, etc.
    │   ├── useGateway.ts           # useGatewayHealth, useGatewayStats
    │   └── useAnalytics.ts         # useSummary, useStatistics, useSensorStats, useAlerts
    ├── types/                      # ── TypeScript interfaces (mirror backend DTOs) ──
    │   ├── sensor.types.ts         # SensorReading, SensorType, SensorStatistics
    │   ├── alert.types.ts          # AlertEvent, Severity
    │   ├── gateway.types.ts        # GatewayHealth, GatewayStats
    │   ├── simulator.types.ts      # SimulatorStatus, SimulatorResponse
    │   └── analytics.types.ts      # AnalyticsSummary
    ├── components/
    │   ├── layout/
    │   │   ├── Sidebar.tsx         # Nav links with icons
    │   │   └── Header.tsx          # Title + connection status indicator
    │   ├── ui/
    │   │   ├── StatCard.tsx        # Metric card (icon, label, value)
    │   │   ├── Badge.tsx           # Severity badge
    │   │   ├── LoadingSpinner.tsx
    │   │   ├── ErrorAlert.tsx      # Error display with retry
    │   │   └── EmptyState.tsx
    │   └── charts/
    │       └── SensorLineChart.tsx  # Recharts time-series (Phase 2)
    ├── pages/
    │   ├── DashboardPage.tsx       # System overview with StatCards
    │   ├── SensorsPage.tsx         # Table of all sensors + stats
    │   ├── SensorDetailPage.tsx    # Single sensor deep-dive
    │   ├── AlertsPage.tsx          # Alert history with severity colors
    │   ├── GatewayPage.tsx         # Gateway processing metrics
    │   ├── SimulatorPage.tsx       # Start/stop/trigger controls
    │   └── NotFoundPage.tsx
    ├── router/index.tsx            # createBrowserRouter config
    └── lib/
        ├── constants.ts            # Polling intervals, sensor metadata
        └── formatters.ts           # formatNumber, formatTimestamp
```

---

## 4. Phase 1 — Live Monitoring Dashboard

> **Aligns with:** Backend Phase 1 (Weeks 1–3) | **Effort:** 3–5 days | **Complexity:** Medium
> **Backend dependency:** None — all APIs already exist

### Step 1: Project Scaffolding

```bash
npm create vite@latest dashboard -- --template react-ts
cd dashboard
npm install axios @tanstack/react-query react-router-dom recharts lucide-react
npm install -D tailwindcss @tailwindcss/vite
npm install -D @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom msw
```

### Step 2: Vite Config with Dev Proxy

```typescript
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api/simulator': { target: 'http://localhost:8081', changeOrigin: true },
      '/api/gateway':   { target: 'http://localhost:8082', changeOrigin: true },
      '/api/analytics': { target: 'http://localhost:8083', changeOrigin: true },
    },
  },
});
```

> 🎓 **Why a dev proxy?** Browsers enforce Same-Origin Policy. Your React app on :5173 can't call :8081 directly. The Vite proxy intercepts `/api/*`, forwards to the correct backend, returns the response — transparent to your code. Axios uses relative paths (`/api/...`), no CORS needed.


### Step 3: TypeScript Types (Mirror Backend DTOs)

**`src/types/sensor.types.ts`:**
```typescript
export type SensorType = 'TEMPERATURE' | 'HUMIDITY' | 'MOTION' | 'LIGHT' | 'PRESSURE';

export interface SensorReading {
  readingId: string;       // UUID
  sensorId: string;        // e.g., "temp-sensor-001"
  sensorType: SensorType;
  value: number;
  unit: string;            // e.g., "°C"
  location: string;        // e.g., "living-room"
  timestamp: string;       // ISO-8601 Instant
  metadata: Record<string, unknown>;
}

export interface SensorStatistics {
  sensorId: string;
  sensorType: SensorType;
  location: string;
  count: number;
  sum: number;
  min: number;
  max: number;
  average: number;
  lastUpdated: string;
}

export const SENSOR_TYPE_META: Record<SensorType, { label: string; unit: string; color: string }> = {
  TEMPERATURE: { label: 'Temperature', unit: '°C',  color: '#ef4444' },
  HUMIDITY:    { label: 'Humidity',    unit: '%',   color: '#3b82f6' },
  MOTION:     { label: 'Motion',      unit: '',    color: '#f59e0b' },
  LIGHT:      { label: 'Light',       unit: 'lux', color: '#eab308' },
  PRESSURE:   { label: 'Pressure',    unit: 'hPa', color: '#8b5cf6' },
};
```

> 🎓 **Why mirror DTOs?** Spring Boot serializes Java objects → JSON. TypeScript interfaces describe that same JSON shape. If the backend changes a field, the TypeScript compiler catches it immediately.

**`src/types/alert.types.ts`:**
```typescript
import type { SensorReading } from './sensor.types';
export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export interface AlertEvent {
  alertId: string;
  triggeringReading: SensorReading;
  severity: Severity;
  message: string;
  timestamp: string;
  threshold: number;
  actualValue: number;
  correlationId: string;
}

export const SEVERITY_CONFIG: Record<Severity, { color: string; bg: string; label: string }> = {
  INFO:     { color: 'text-blue-700',  bg: 'bg-blue-100',  label: 'Info' },
  WARNING:  { color: 'text-amber-700', bg: 'bg-amber-100', label: 'Warning' },
  CRITICAL: { color: 'text-red-700',   bg: 'bg-red-100',   label: 'Critical' },
};
```

**`src/types/gateway.types.ts`:**
```typescript
export interface GatewayHealth { status: string; service: string; timestamp: string; }

export interface GatewayStats {
  messagesReceived: number;
  messagesProcessed: number;
  validationFailures: number;
  anomaliesDetected: number;
  validationFailureRate: string;
  anomalyRate: string;
  timestamp: string;
}
```

**`src/types/simulator.types.ts`:**
```typescript
import type { SensorType } from './sensor.types';

export interface SimulatorSensor { id: string; type: SensorType; location: string; }
export interface SimulatorStatus {
  running: boolean; interval: number; sensorCount: number; sensors: SimulatorSensor[];
}
export interface SimulatorResponse { message: string; status?: string; sensorCount?: number; }
```

**`src/types/analytics.types.ts`:**
```typescript
import type { SensorStatistics } from './sensor.types';

export interface AnalyticsSummary {
  sensorsTracked: number;
  totalReadingsProcessed: number;
  alertsCount: number;
  statistics: SensorStatistics[];
  generatedAt: string;
}
```

### Step 4: Axios Instance & API Modules

**`src/api/axios.ts`:**
```typescript
import axios from 'axios';

const api = axios.create({
  baseURL: '',
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error.response?.data?.message || error.message || 'An unexpected error occurred';
    console.error(`[API Error] ${error.config?.method?.toUpperCase()} ${error.config?.url}: ${message}`);
    return Promise.reject(error);
  }
);

export default api;
```

> 🎓 **Why an Axios instance?** Create ONE configured instance. Later (Phase 3), add a request interceptor to attach the JWT `Authorization` header. One change, all requests get auth.

**`src/api/analytics.api.ts`:**
```typescript
import api from './axios';
import type { AnalyticsSummary } from '../types/analytics.types';
import type { SensorStatistics } from '../types/sensor.types';
import type { AlertEvent } from '../types/alert.types';

export const analyticsApi = {
  getSummary: () =>
    api.get<AnalyticsSummary>('/api/analytics/summary').then(r => r.data),
  getStatistics: () =>
    api.get<SensorStatistics[]>('/api/analytics/statistics').then(r => r.data),
  getSensorStatistics: (sensorId: string) =>
    api.get<SensorStatistics>(`/api/analytics/statistics/${sensorId}`).then(r => r.data),
  getAlerts: (limit = 50) =>
    api.get<AlertEvent[]>('/api/analytics/alerts', { params: { limit } }).then(r => r.data),
  getHealth: () =>
    api.get('/api/analytics/health').then(r => r.data),
};
```

**`src/api/simulator.api.ts`:**
```typescript
import api from './axios';
import type { SimulatorStatus, SimulatorResponse } from '../types/simulator.types';

export const simulatorApi = {
  getStatus: () => api.get<SimulatorStatus>('/api/simulator/status').then(r => r.data),
  trigger: () => api.post<SimulatorResponse>('/api/simulator/trigger').then(r => r.data),
  start: () => api.post<SimulatorResponse>('/api/simulator/start').then(r => r.data),
  stop: () => api.post<SimulatorResponse>('/api/simulator/stop').then(r => r.data),
};
```

**`src/api/gateway.api.ts`:**
```typescript
import api from './axios';
import type { GatewayHealth, GatewayStats } from '../types/gateway.types';

export const gatewayApi = {
  getHealth: () => api.get<GatewayHealth>('/api/gateway/health').then(r => r.data),
  getStats: () => api.get<GatewayStats>('/api/gateway/stats').then(r => r.data),
};
```

### Step 5: TanStack Query Hooks

**`src/hooks/useAnalytics.ts`:**
```typescript
import { useQuery } from '@tanstack/react-query';
import { analyticsApi } from '../api/analytics.api';

const POLL_INTERVAL = 5_000;

export function useSummary() {
  return useQuery({
    queryKey: ['analytics', 'summary'],
    queryFn: analyticsApi.getSummary,
    refetchInterval: POLL_INTERVAL,
  });
}

export function useStatistics() {
  return useQuery({
    queryKey: ['analytics', 'statistics'],
    queryFn: analyticsApi.getStatistics,
    refetchInterval: POLL_INTERVAL,
  });
}

export function useSensorStatistics(sensorId: string) {
  return useQuery({
    queryKey: ['analytics', 'statistics', sensorId],
    queryFn: () => analyticsApi.getSensorStatistics(sensorId),
    refetchInterval: POLL_INTERVAL,
    enabled: !!sensorId,
  });
}

export function useAlerts(limit = 50) {
  return useQuery({
    queryKey: ['analytics', 'alerts', limit],
    queryFn: () => analyticsApi.getAlerts(limit),
    refetchInterval: POLL_INTERVAL,
  });
}
```

> 🎓 **TanStack Query's refetchInterval:** Setting `refetchInterval: 5000` re-runs the query every 5s automatically. In Phase 2, we'll replace with SSE push for true real-time.

**`src/hooks/useSimulator.ts`:**
```typescript
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { simulatorApi } from '../api/simulator.api';

export function useSimulatorStatus() {
  return useQuery({ queryKey: ['simulator', 'status'], queryFn: simulatorApi.getStatus, refetchInterval: 5_000 });
}

export function useSimulatorTrigger() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: simulatorApi.trigger,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['analytics'] }),
  });
}

export function useSimulatorStart() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: simulatorApi.start,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['simulator', 'status'] }),
  });
}

export function useSimulatorStop() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: simulatorApi.stop,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['simulator', 'status'] }),
  });
}
```

**`src/hooks/useGateway.ts`:**
```typescript
import { useQuery } from '@tanstack/react-query';
import { gatewayApi } from '../api/gateway.api';

export function useGatewayHealth() {
  return useQuery({ queryKey: ['gateway', 'health'], queryFn: gatewayApi.getHealth, refetchInterval: 10_000 });
}

export function useGatewayStats() {
  return useQuery({ queryKey: ['gateway', 'stats'], queryFn: gatewayApi.getStats, refetchInterval: 5_000 });
}
```

### Step 6: Application Shell (Layout + Routing)

**`src/main.tsx`:**
```typescript
import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { router } from './router';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 2, staleTime: 2_000, refetchOnWindowFocus: true } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </React.StrictMode>
);
```

**`src/router/index.tsx`:**
```typescript
import { createBrowserRouter } from 'react-router-dom';
import App from '../App';
import DashboardPage from '../pages/DashboardPage';
import SensorsPage from '../pages/SensorsPage';
import SensorDetailPage from '../pages/SensorDetailPage';
import AlertsPage from '../pages/AlertsPage';
import GatewayPage from '../pages/GatewayPage';
import SimulatorPage from '../pages/SimulatorPage';
import NotFoundPage from '../pages/NotFoundPage';

export const router = createBrowserRouter([{
  path: '/',
  element: <App />,
  children: [
    { index: true, element: <DashboardPage /> },
    { path: 'sensors', element: <SensorsPage /> },
    { path: 'sensors/:sensorId', element: <SensorDetailPage /> },
    { path: 'alerts', element: <AlertsPage /> },
    { path: 'gateway', element: <GatewayPage /> },
    { path: 'simulator', element: <SimulatorPage /> },
    { path: '*', element: <NotFoundPage /> },
  ],
}]);
```

**`src/App.tsx`:**
```typescript
import { Outlet } from 'react-router-dom';
import Sidebar from './components/layout/Sidebar';
import Header from './components/layout/Header';

export default function App() {
  return (
    <div className="flex h-screen bg-gray-50">
      <Sidebar />
      <div className="flex flex-1 flex-col overflow-hidden">
        <Header />
        <main className="flex-1 overflow-y-auto p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
```

**`src/components/layout/Sidebar.tsx`:**
```typescript
import { NavLink } from 'react-router-dom';
import { LayoutDashboard, Cpu, Bell, Router, Play, Activity } from 'lucide-react';

const navItems = [
  { to: '/',          icon: LayoutDashboard, label: 'Dashboard' },
  { to: '/sensors',   icon: Cpu,             label: 'Sensors' },
  { to: '/alerts',    icon: Bell,            label: 'Alerts' },
  { to: '/gateway',   icon: Router,          label: 'Gateway' },
  { to: '/simulator', icon: Play,            label: 'Simulator' },
];

export default function Sidebar() {
  return (
    <aside className="flex w-64 flex-col bg-gray-900 text-gray-300">
      <div className="flex h-16 items-center gap-2 px-6">
        <Activity className="h-6 w-6 text-emerald-400" />
        <span className="text-lg font-bold text-white">SmartHome</span>
      </div>
      <nav className="flex-1 space-y-1 px-3 py-4">
        {navItems.map(({ to, icon: Icon, label }) => (
          <NavLink key={to} to={to} end={to === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                isActive ? 'bg-gray-800 text-white' : 'hover:bg-gray-800 hover:text-white'
              }`}>
            <Icon className="h-5 w-5" />{label}
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}
```

**`src/components/layout/Header.tsx`:**
```typescript
import { useGatewayHealth } from '../../hooks/useGateway';

export default function Header() {
  const { data: health } = useGatewayHealth();
  return (
    <header className="flex h-16 items-center justify-between border-b bg-white px-6">
      <h2 className="text-lg font-semibold text-gray-700">IoT Smart Home Monitor</h2>
      <div className="flex items-center gap-2 text-sm">
        <div className={`h-2.5 w-2.5 rounded-full ${health?.status === 'UP' ? 'bg-green-500' : 'bg-red-500'}`} />
        <span className="text-gray-500">{health?.status === 'UP' ? 'Backend Connected' : 'Backend Offline'}</span>
      </div>
    </header>
  );
}
```

### Step 7: Core Pages

#### DashboardPage — System Overview
```typescript
import { Cpu, Activity, AlertTriangle, BarChart3 } from 'lucide-react';
import { useSummary } from '../hooks/useAnalytics';
import StatCard from '../components/ui/StatCard';

export default function DashboardPage() {
  const { data: summary, isLoading, error, refetch } = useSummary();
  if (isLoading) return <LoadingSpinner />;
  if (error) return <ErrorAlert message="Failed to load summary" onRetry={refetch} />;
  if (!summary) return null;

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-gray-900">System Overview</h1>
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard icon={Cpu}           label="Sensors Tracked"  value={summary.sensorsTracked} />
        <StatCard icon={Activity}      label="Total Readings"   value={summary.totalReadingsProcessed.toLocaleString()} />
        <StatCard icon={AlertTriangle} label="Alerts"           value={summary.alertsCount} color="red" />
        <StatCard icon={BarChart3}     label="Sensor Types"     value={new Set(summary.statistics.map(s => s.sensorType)).size} />
      </div>
    </div>
  );
}
```

#### Other Pages (Summary)
- **SensorsPage** — Table with columns: Sensor ID (link), Type, Location, Min, Max, Avg, Readings
- **SensorDetailPage** — `useParams()` + `useSensorStatistics(sensorId)`, 4 stat cards, chart placeholder for Phase 2
- **AlertsPage** — `useAlerts()`, severity-colored cards, empty state
- **GatewayPage** — `useGatewayStats()` + `useGatewayHealth()`, 4 StatCards
- **SimulatorPage** — Start/Stop/Trigger buttons, sensor grid

### Phase 1 Files Summary (~25 files)

| Category | Files |
|----------|-------|
| Config | vite.config.ts, tsconfig.json, package.json, index.css |
| Types | sensor.types.ts, alert.types.ts, gateway.types.ts, simulator.types.ts, analytics.types.ts |
| API | axios.ts, analytics.api.ts, simulator.api.ts, gateway.api.ts |
| Hooks | useAnalytics.ts, useSimulator.ts, useGateway.ts |
| Layout | Sidebar.tsx, Header.tsx |
| UI | StatCard.tsx, LoadingSpinner.tsx, ErrorAlert.tsx, EmptyState.tsx, Badge.tsx |
| Pages | DashboardPage, SensorsPage, SensorDetailPage, AlertsPage, GatewayPage, SimulatorPage, NotFoundPage |
| Router | router/index.tsx |
| Entry | main.tsx, App.tsx |

---

## 5. Phase 2 — Historical Analytics & Device Management

> **Aligns with:** Backend Phase 2 (Weeks 4–8) | **Effort:** 5–7 days | **Complexity:** Medium-High
> **Depends on:** persistence-service (TimescaleDB), device-registry-service

### New API Modules

**`src/api/history.api.ts`:**
```typescript
export interface HistoryQuery { sensorId: string; from: string; to: string; interval?: string; }
export interface AggregatedDataPoint { timestamp: string; min: number; max: number; avg: number; count: number; }

export const historyApi = {
  getReadings: (q: HistoryQuery) =>
    api.get<SensorReading[]>('/api/history/readings', { params: q }).then(r => r.data),
  getAggregated: (q: HistoryQuery) =>
    api.get<AggregatedDataPoint[]>('/api/history/aggregate', { params: q }).then(r => r.data),
};
```

**`src/api/devices.api.ts`:**
```typescript
export type DeviceState = 'REGISTERED' | 'ACTIVE' | 'MAINTENANCE' | 'DECOMMISSIONED';

export interface Device {
  deviceId: string; name: string; type: string; location: string; state: DeviceState;
  firmwareVersion: string; registeredAt: string; lastSeenAt: string; metadata: Record<string, unknown>;
}

export const devicesApi = {
  getAll: () => api.get<Device[]>('/api/devices').then(r => r.data),
  getById: (id: string) => api.get<Device>(`/api/devices/${id}`).then(r => r.data),
  create: (d) => api.post<Device>('/api/devices', d).then(r => r.data),
  update: (id, u) => api.put<Device>(`/api/devices/${id}`, u).then(r => r.data),
  transition: (id, state) => api.post<Device>(`/api/devices/${id}/transition`, { state }).then(r => r.data),
  delete: (id) => api.delete(`/api/devices/${id}`).then(r => r.data),
};
```

### New Pages
- **HistoryPage.tsx:** Date range picker (1h/6h/24h/7d/custom) + sensor selector → Recharts `<LineChart>`
- **DevicesPage.tsx:** Table with state badges, "Add Device" modal, row actions
- **DeviceDetailPage.tsx:** Device info + lifecycle state transitions

### SSE Integration Hook (Replaces Polling)
```typescript
// src/hooks/useSSE.ts
export function useSSE(url: string) {
  const qc = useQueryClient();
  useEffect(() => {
    const es = new EventSource(url);
    es.addEventListener('sensor-update', (e) => {
      const data = JSON.parse(e.data);
      qc.setQueryData(['analytics', 'statistics', data.sensorId], data);
      qc.invalidateQueries({ queryKey: ['analytics', 'summary'] });
    });
    es.addEventListener('alert', () => {
      qc.invalidateQueries({ queryKey: ['analytics', 'alerts'] });
    });
    es.onerror = () => console.warn('SSE reconnecting...');
    return () => es.close();
  }, [url, qc]);
}
```

> 🎓 **SSE vs. WebSocket:** SSE = one-way server→client push, auto-reconnect, plain HTTP. Perfect for dashboards. Browser's `EventSource` handles reconnection automatically.

---

## 6. Phase 3 — Authentication & Security

> **Aligns with:** Backend Phase 3 (Weeks 9–12) | **Effort:** 3–5 days
> **Depends on:** IAM Service (OAuth2/JWT), Spring Cloud Gateway

- **AuthContext.tsx** — JWT in memory (not localStorage), `login()`, `logout()`, `hasRole()`
- **Axios interceptor** — attach `Bearer` token on requests, redirect to `/login` on 401
- **ProtectedRoute.tsx** — wraps authenticated routes, role-based access
- **LoginPage.tsx** — username/password form
- **Simplified proxy** — all through Spring Cloud Gateway on `:8080`

---

## 7. Phase 4 — Containerization & CI/CD

> **Aligns with:** Backend Phase 4 (Weeks 13–16) | **Effort:** 2–3 days

- **Multi-stage Dockerfile:** Node build → nginx:alpine (~40 MB image)
- **nginx.conf:** SPA fallback, API proxy, SSE no-buffering, static asset caching
- **docker-compose:** `dashboard` service on port 3000
- **Helm chart:** `dashboard/helm/` directory

---

## 8. Development Workflow

```bash
# Terminal 1: Infrastructure
docker-compose up -d                                     # RabbitMQ

# Terminal 2-4: Backend services
cd sensor-simulator-service && ../mvnw spring-boot:run   # :8081
cd gateway-service && ../mvnw spring-boot:run             # :8082
cd processing-service && ../mvnw spring-boot:run          # :8083

# Terminal 5: Frontend
cd dashboard && npm run dev                              # http://localhost:5173
```

---

## 9. Testing Strategy

- **Vitest + React Testing Library + MSW** for all test layers
- MSW intercepts at network level — more realistic than mocking Axios
- Test hooks with `renderHook`, components with RTL, types via compiler

---

## 10. Estimated Effort

| Phase | Effort | Backend Dependency |
|-------|--------|--------------------|
| Phase 1: Live Dashboard | 3–5 days | **None** (all APIs exist) |
| Phase 2: History + Devices | 5–7 days | persistence-service, device-registry |
| Phase 3: Auth | 3–5 days | IAM service, Spring Cloud Gateway |
| Phase 4: Containers | 2–3 days | Docker, K8s cluster |
| **Total** | **13–20 days** | |

---

## 11. Key Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| Vite dev proxy (not CORS) | Zero backend changes needed |
| TanStack Query (not Redux) | Purpose-built for server state; built-in caching/polling |
| Polling first → SSE later | Simpler, works today, progressive enhancement |
| Types mirror DTOs exactly | Compiler catches contract drift |
| Axios instance (not fetch) | Interceptors for auth (Phase 3) |
| Tailwind (not CSS modules) | Fast prototyping, consistent design |
| Memory token storage | More secure than localStorage against XSS |
| Multi-stage Docker build | Tiny production image (~40 MB) |