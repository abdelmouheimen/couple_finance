import type { ExpoConfig } from "expo/config";

// Public, non-secret configuration only. The API base URL is read from EXPO_PUBLIC_API_BASE_URL
// (inlined at build time); see README.md. Never put secrets in EXPO_PUBLIC_* variables.
const config: ExpoConfig = {
    name: "CoupleFinance",
    slug: "couplefinance",
    version: "0.1.0",
    scheme: "couplefinance",
    orientation: "portrait",
    // The approved design is dark only.
    userInterfaceStyle: "dark",
    plugins: ["expo-router", "expo-secure-store", "expo-font"],
    experiments: { typedRoutes: true },
    ios: { bundleIdentifier: "com.couplefinance.app", supportsTablet: false },
    android: { package: "com.couplefinance.app" },
    // Expo project used by EAS Build (eas.json); public identifier, not a secret.
    extra: { eas: { projectId: "2d709c30-c656-48d5-8d3b-db473ab40d9c" } },
};

export default config;
