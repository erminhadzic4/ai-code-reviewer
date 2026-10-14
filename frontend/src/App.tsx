import { Route, Routes } from 'react-router'
import { AppLayout } from './components/AppLayout'

function App() {
  return (
    <Routes>
      <Route path="*" element={<AppLayout />} />
    </Routes>
  )
}

export default App
