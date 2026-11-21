import Constants from 'expo-constants';

// Comes from AppStore/app.json through app.config.ts.
const extra = Constants.expoConfig?.extra as { privacyUrl: string; supportUrl: string; termsUrl: string };

export const PRIVACY_URL = extra.privacyUrl;
export const SUPPORT_URL = extra.supportUrl;
export const TERMS_URL = extra.termsUrl;
export const APP_VERSION = Constants.expoConfig?.version ?? '';
