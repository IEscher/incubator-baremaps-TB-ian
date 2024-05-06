import style from './grayscale.js';
import {Color} from "../utils/color.js";

export default Object.entries(style).reduce((acc, [key, value]) => {
    let color = Color.fromString(value);
    if (color == null) {
        acc[key] = value;
        return acc;
    } else {
        acc[key] = color.grayscale().lighten(0.1).toString();
        return acc;
    }
}, {});
