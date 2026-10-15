import { Route, Routes } from 'react-router'
import { RequireAuth } from './auth/RequireAuth'
import { AppLayout } from './components/AppLayout'
import { AuthPage } from './pages/AuthPage'
import { HistoryPage } from './pages/HistoryPage'
import { LandingPage } from './pages/LandingPage'
import { NewReviewPage } from './pages/NewReviewPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { ReviewPage } from './pages/ReviewPage'

function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<LandingPage />} />
        <Route path="login" element={<AuthPage key="login" mode="login" />} />
        <Route path="register" element={<AuthPage key="register" mode="register" />} />
        <Route
          path="reviews"
          element={
            <RequireAuth>
              <HistoryPage />
            </RequireAuth>
          }
        />
        <Route
          path="reviews/new"
          element={
            <RequireAuth>
              <NewReviewPage />
            </RequireAuth>
          }
        />
        <Route
          path="reviews/:id"
          element={
            <RequireAuth>
              <ReviewPage />
            </RequireAuth>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default App
