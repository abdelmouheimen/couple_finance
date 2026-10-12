import type { IconName } from "@/shared/ui/theme/icons";
import { categoryColors, type CategoryColorName } from "@/shared/ui/theme/tokens";

/** Icon keys served by the category API (`Category.icon`) mapped to the approved icon set. */
const ICON_BY_KEY: Readonly<Record<string, IconName>> = {
    cart: "cart",
    home: "home",
    bolt: "flash",
    car: "car",
    restaurant: "utensils",
    heart: "heart",
    gamepad: "gamepad",
    bag: "bag",
    plane: "plane",
    child: "baby",
    gift: "gift",
    dots: "more",
    tag: "tag",
};

/** Tile colour of each system category in the approved palette (design/tokens.json `category`). */
const COLOR_BY_CODE: Readonly<Record<string, CategoryColorName>> = {
    GROCERIES: "groceries",
    HOUSING: "housing",
    UTILITIES: "utilities",
    TRANSPORT: "transport",
    RESTAURANTS: "restaurants",
    HEALTH: "health",
    LEISURE: "leisure",
    SHOPPING: "shopping",
    TRAVEL: "travel",
    CHILDREN: "children",
    GIFTS: "gifts",
    OTHER: "other",
};

export interface CategoryVisual {
    icon: IconName;
    color: string;
}

/**
 * Icon and tile colour of a category. The icon follows the key served by the API; the colour comes
 * from the approved palette (never an arbitrary colour): custom and unknown categories use the
 * neutral "other" colour and the tag icon.
 */
export function categoryVisual(
    category: { icon?: string; systemCode?: string } | undefined,
): CategoryVisual {
    const colorName = COLOR_BY_CODE[category?.systemCode ?? ""] ?? "other";
    return {
        icon: ICON_BY_KEY[category?.icon ?? ""] ?? "tag",
        color: categoryColors[colorName],
    };
}
