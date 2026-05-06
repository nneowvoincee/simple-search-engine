import {createBrowserRouter} from "react-router-dom";
import SearchEngine from "../page/SearchEngine";




const router = createBrowserRouter([
    {
        path:'/searchengine',
        element:<SearchEngine/>
    }

])

export default router