const expoConfig = require("eslint-config-expo/flat");
const tsPlugin = require("@typescript-eslint/eslint-plugin");

module.exports = [
    ...expoConfig,
    { ignores: ["node_modules", ".expo", "dist", "src/shared/api/generated/**"] },
    {
        files: ["**/*.ts", "**/*.tsx"],
        plugins: { "@typescript-eslint": tsPlugin },
        rules: {
            // CLAUDE.md §14: no `any` without justification (use a scoped eslint-disable with a reason).
            "@typescript-eslint/no-explicit-any": "error",
        },
    },
];
