import type { ConfigContext, ExpoConfig } from 'expo/config';

import store from './AppStore/app.json';

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...(config as ExpoConfig),
  ios: {
    ...config.ios,
    // Set by the release workflow to the git commit count, so every upload to
    // App Store Connect is unique and increasing.
    buildNumber: process.env.BUILD_NUMBER ?? '1',
  },
  // The store listing's links, so the app and App Store Connect never disagree.
  extra: {
    privacyUrl: store.privacy_url,
    supportUrl: store.support_url,
    termsUrl: store.terms_url,
  },
});
