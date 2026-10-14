import { Route, Routes } from 'react-router'
import { AppLayout } from './components/AppLayout'
import { AuthPage } from './pages/AuthPage'
import { LandingPage } from './pages/LandingPage'

function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<LandingPage />} />
        <Route path="login" element={<AuthPage key="login" mode="login" />} />
        <Route path="register" element={<AuthPage key="register" mode="register" />} />
      </Route>
    </Routes>
  )
}

export default App
