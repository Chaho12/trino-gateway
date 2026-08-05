export interface BackendData {
  name: string;
  proxyTo: string;
  active: boolean;
  routingGroups: string[];
  externalUrl: string;
  queued: number;
  running: number;
  status: string;
}
