import { Route, Routes } from 'react-router'
import { RequireAuth } from './auth/RequireAuth'
import { AppLayout } from './components/AppLayout'
import { AuthPage } from './pages/AuthPage'
import { LandingPage } from './pages/LandingPage'
import { ReviewPage } from './pages/ReviewPage'

function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<LandingPage />} />
        <Route path="login" element={<AuthPage key="login" mode="login" />} />
        <Route path="register" element={<AuthPage key="register" mode="register" />} />
        <Route
          path="reviews/:id"
          element={
            <RequireAuth>
              <ReviewPage />
            </RequireAuth>
          }
        />
      </Route>
    </Routes>
  )
}

export default App
