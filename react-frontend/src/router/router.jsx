import {createBrowserRouter} from "react-router-dom";
import HackerSearch from "../page/searchengine";



const router = createBrowserRouter([
    {
        path:'/searchengine',
        element:<HackerSearch/>
    }

])

export default router