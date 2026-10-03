import type { ExpoConfig } from "expo/config";

// Public, non-secret configuration only. The API base URL is read from EXPO_PUBLIC_API_BASE_URL
// (inlined at build time); see README.md. Never put secrets in EXPO_PUBLIC_* variables.
const config: ExpoConfig = {
    name: "CoupleFinance",
    slug: "couplefinance",
    version: "0.1.0",
    scheme: "couplefinance",
    orientation: "portrait",
    userInterfaceStyle: "automatic",
    plugins: ["expo-router", "expo-secure-store"],
    experiments: { typedRoutes: true },
    ios: { bundleIdentifier: "com.couplefinance.app", supportsTablet: false },
    android: { package: "com.couplefinance.app" },
};

export default config;
