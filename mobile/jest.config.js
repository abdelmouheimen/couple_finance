module.exports = {
    preset: "jest-expo",
    testTimeout: 30000,
    moduleNameMapper: { "^@/(.*)$": "<rootDir>/src/$1" },
    testPathIgnorePatterns: ["/node_modules/"],
};
