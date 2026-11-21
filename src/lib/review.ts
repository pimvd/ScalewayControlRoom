import * as SecureStore from 'expo-secure-store';
import * as StoreReview from 'expo-store-review';

// Asks for a rating after a server was started or stopped successfully: the
// moment the app has just been useful. Apple shows the prompt at most three
// times a year whatever we ask, so asking at a few milestones is enough.
const COUNT = 'review.successfulActions';
const ASK_AT = [3, 20, 60];

export function shouldAskForReview(successfulActions: number): boolean {
  return ASK_AT.includes(successfulActions);
}

export async function noteSuccessfulAction(): Promise<void> {
  const count = Number((await SecureStore.getItemAsync(COUNT)) ?? 0) + 1;
  await SecureStore.setItemAsync(COUNT, String(count));
  if (shouldAskForReview(count) && (await StoreReview.hasAction())) {
    await StoreReview.requestReview();
  }
}
