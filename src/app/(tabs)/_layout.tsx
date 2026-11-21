import { NativeTabs } from 'expo-router/native-tabs';

export default function TabsLayout() {
  return (
    <NativeTabs>
      <NativeTabs.Trigger name="index">
        <NativeTabs.Trigger.Label>Instances</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="server.rack" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="serverless">
        <NativeTabs.Trigger.Label>Serverless</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="bolt" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="costs">
        <NativeTabs.Trigger.Label>Costs</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="eurosign.circle" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="settings">
        <NativeTabs.Trigger.Label>Settings</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="gearshape" />
      </NativeTabs.Trigger>
    </NativeTabs>
  );
}
