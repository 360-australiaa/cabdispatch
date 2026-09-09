/**
 * The Fleet & Drivers data layer.
 *
 * This used to be one 804-line `pages/fleet/api.ts`. Phase 0 split it by
 * domain -- vehicles, drivers, devices, the temporary bulk wipes, and the two
 * page-size caps all four share -- with no change to a single function body.
 *
 * This barrel re-exports EVERY name the old module exported, under the same
 * `@/pages/fleet/api` specifier, so no call site anywhere else in the app had
 * to change its import path.
 */

export { PAGE_LIMIT } from "./constants";

export {
  downloadVehicleEvidencePack,
  useComplianceExpiry,
  useCreateVehicle,
  useDeleteVehicle,
  useGeneratePairingCode,
  useUpdateVehicle,
  useVehicle,
  useVehicleLifetimeTotals,
  useVehicleLiveOptions,
  useVehicleOptions,
  useVehiclePilotReport,
  useVehicles,
  type VehicleFilters,
} from "./vehicles";

export {
  useAcknowledgeFatigueAlert,
  useCreateDriver,
  useDeleteDriver,
  useDriverCompliance,
  useDriverPhoto,
  useDrivers,
  useOpenFatigueAlerts,
  useUpdateDriverCompliance,
  useUploadDriverPhoto,
  type CreateDriverInput,
  type DriverFilters,
} from "./drivers";

export {
  useCancelForceUpdate,
  useCreateDevice,
  useDeleteDevice,
  useDeviceDetailQuery,
  useDeviceOptions,
  useDevices,
  useForceUpdate,
  useForceUpdateAll,
  useKioskLock,
  useLocateDevice,
  useRestartApp,
  useRotateDeviceSecret,
  useSetDeviceRevoked,
  useUpdateDevice,
  type DeviceFilters,
} from "./devices";

export {
  useForceWipeAllFleetData,
  useWipeAllFleetData,
  type FleetForceWipeFailure,
  type FleetForceWipeResult,
  type WipeAllFleetDataResult,
} from "./wipe";
