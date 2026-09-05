import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, CheckCircle2, ImageOff, RotateCcw, Sparkles } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Modal,
  PageHeader,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import {
  useTenantQuery,
  useUpdateTenantThemeMutation,
  type TenantTheme,
} from "@/hooks/useWhite-labelSettings";

const HEX_RE = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$/;

/** Non-nullable form-local mirror of TenantTheme — inputs never hold `null`, only `""`. */
interface ThemeFormValues {
  logo_url: string;
  primary_color: string;
  accent_color: string;
}

/** Brand defaults, matching the app shell's own indigo/gold palette (index.css). */
const BRAND_DEFAULT: ThemeFormValues = {
  logo_url: "",
  primary_color: "#2A1C58",
  accent_color: "#F4C300",
};

/** Example alternate-brand preset for the "Lilly Cabs" demo tenant — pink-toned. */
const LILLY_CABS_PRESET: ThemeFormValues = {
  logo_url: "https://placehold.co/240x64/D6336C/ffffff?text=Lilly+Cabs",
  primary_color: "#D6336C",
  accent_color: "#FFB3C6",
};

function normalizeTheme(theme: TenantTheme | null | undefined): ThemeFormValues {
  return {
    logo_url: theme?.logo_url ?? "",
    primary_color: theme?.primary_color ?? BRAND_DEFAULT.primary_color,
    accent_color: theme?.accent_color ?? BRAND_DEFAULT.accent_color,
  };
}

function ColorField({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  disabled: boolean;
}) {
  const valid = HEX_RE.test(value);
  return (
    <div>
      <label className="mb-1.5 block text-sm font-medium text-foreground">{label}</label>
      <div className="flex items-center gap-2">
        <input
          type="color"
          value={valid ? value : "#000000"}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          aria-label={`${label} color picker`}
          className="h-10 w-12 shrink-0 cursor-pointer rounded-md border border-input bg-background disabled:cursor-not-allowed disabled:opacity-50"
        />
        <Input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          placeholder="#RRGGBB"
          spellCheck={false}
          className={!valid ? "border-destructive focus-visible:ring-destructive" : undefined}
        />
      </div>
      {!valid && (
        <p className="mt-1 text-xs text-destructive">Enter a valid hex color, e.g. #2A1C58.</p>
      )}
    </div>
  );
}

export default function WhiteLabelSettingsPage() {
  const { user } = useAuth();
  const canEdit = user?.role === "owner" || user?.role === "admin";

  const tenantQuery = useTenantQuery();
  const updateMutation = useUpdateTenantThemeMutation();

  const savedTheme = useMemo(() => normalizeTheme(tenantQuery.data?.theme_json), [tenantQuery.data]);

  const [logoUrl, setLogoUrl] = useState(savedTheme.logo_url);
  const [primaryColor, setPrimaryColor] = useState(savedTheme.primary_color);
  const [accentColor, setAccentColor] = useState(savedTheme.accent_color);
  const [logoFailed, setLogoFailed] = useState(false);
  const [resetModalOpen, setResetModalOpen] = useState(false);

  // Sync local form state whenever the server record (re)loads.
  useEffect(() => {
    if (!tenantQuery.data) return;
    const t = normalizeTheme(tenantQuery.data.theme_json);
    setLogoUrl(t.logo_url);
    setPrimaryColor(t.primary_color);
    setAccentColor(t.accent_color);
    setLogoFailed(false);
  }, [tenantQuery.data]);

  const colorsValid = HEX_RE.test(primaryColor) && HEX_RE.test(accentColor);
  const isDirty =
    logoUrl !== savedTheme.logo_url ||
    primaryColor !== savedTheme.primary_color ||
    accentColor !== savedTheme.accent_color;

  function applyPreset(preset: ThemeFormValues) {
    setLogoUrl(preset.logo_url);
    setPrimaryColor(preset.primary_color);
    setAccentColor(preset.accent_color);
    setLogoFailed(false);
    updateMutation.reset();
  }

  function handleSave() {
    if (!colorsValid) return;
    updateMutation.mutate({
      theme_json: {
        logo_url: logoUrl.trim() || null,
        primary_color: primaryColor,
        accent_color: accentColor,
      },
    });
  }

  function handleRevert() {
    setLogoUrl(savedTheme.logo_url);
    setPrimaryColor(savedTheme.primary_color);
    setAccentColor(savedTheme.accent_color);
    setLogoFailed(false);
    updateMutation.reset();
  }

  function handleConfirmReset() {
    updateMutation.mutate(
      { theme_json: null },
      {
        onSuccess: () => setResetModalOpen(false),
      },
    );
  }

  return (
    <div>
      <PageHeader
        title="White-label Settings"
        description="Brand the dashboard and driver-facing surfaces for your fleet: logo, primary color, and accent color."
        actions={!canEdit ? <Badge variant="outline">View only</Badge> : undefined}
      />

      {tenantQuery.isLoading && (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            Loading tenant branding…
          </CardContent>
        </Card>
      )}

      {tenantQuery.isError && (
        <Card className="border-destructive/50">
          <CardContent className="flex items-start gap-3 py-6">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
            <div>
              <p className="text-sm font-medium text-foreground">
                Couldn't load tenant branding.
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                {(tenantQuery.error as { message?: string })?.message ??
                  "GET /v1/tenants/me failed."}
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      {tenantQuery.isSuccess && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Brand</CardTitle>
              <CardDescription>
                Applied wherever your fleet's identity shows up: dashboard sidebar, driver
                kiosk app, and passenger receipts.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-5">
              <div>
                <label className="mb-1.5 block text-sm font-medium text-foreground">
                  Logo URL
                </label>
                <Input
                  value={logoUrl}
                  onChange={(e) => {
                    setLogoUrl(e.target.value);
                    setLogoFailed(false);
                  }}
                  disabled={!canEdit}
                  placeholder="https://…/logo.png"
                />
              </div>

              <ColorField
                label="Primary color"
                value={primaryColor}
                onChange={setPrimaryColor}
                disabled={!canEdit}
              />
              <ColorField
                label="Accent color"
                value={accentColor}
                onChange={setAccentColor}
                disabled={!canEdit}
              />

              {canEdit && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => applyPreset(LILLY_CABS_PRESET)}
                >
                  <Sparkles className="h-4 w-4" />
                  Fill with Lilly Cabs preset
                </Button>
              )}

              {updateMutation.isError && (
                <p className="flex items-center gap-2 text-sm text-destructive">
                  <AlertTriangle className="h-4 w-4 shrink-0" />
                  {(updateMutation.error as { message?: string })?.message ??
                    "Failed to save branding."}
                </p>
              )}
              {updateMutation.isSuccess && !isDirty && (
                <p className="flex items-center gap-2 text-sm text-success">
                  <CheckCircle2 className="h-4 w-4 shrink-0" />
                  Branding saved.
                </p>
              )}
            </CardContent>
            {canEdit && (
              <CardContent className="flex flex-wrap items-center gap-2 border-t border-border pt-4">
                <Button
                  onClick={handleSave}
                  disabled={!isDirty || !colorsValid || updateMutation.isPending}
                >
                  {updateMutation.isPending ? "Saving…" : "Save changes"}
                </Button>
                <Button variant="ghost" onClick={handleRevert} disabled={!isDirty}>
                  Discard changes
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="ml-auto text-destructive hover:bg-destructive/10"
                  onClick={() => setResetModalOpen(true)}
                >
                  <RotateCcw className="h-4 w-4" />
                  Reset to default
                </Button>
              </CardContent>
            )}
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Live preview</CardTitle>
              <CardDescription>How these colors look across the app shell.</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="overflow-hidden rounded-lg border border-border">
                <div
                  className="flex items-center gap-3 px-4 py-3"
                  style={{ backgroundColor: colorsValid ? primaryColor : BRAND_DEFAULT.primary_color }}
                >
                  {logoUrl && !logoFailed ? (
                    <img
                      src={logoUrl}
                      alt="Tenant logo preview"
                      className="h-8 max-w-[140px] object-contain"
                      onError={() => setLogoFailed(true)}
                    />
                  ) : (
                    <div className="flex h-8 items-center rounded px-2 text-xs font-semibold text-white/90">
                      {logoUrl && logoFailed ? (
                        <span className="flex items-center gap-1">
                          <ImageOff className="h-3.5 w-3.5" /> logo failed to load
                        </span>
                      ) : (
                        (tenantQuery.data?.name ?? "Your Fleet")
                      )}
                    </div>
                  )}
                  <span className="ml-auto text-sm font-medium text-white/90">Dashboard</span>
                </div>

                <div className="flex gap-4 bg-background p-4">
                  <div
                    className="w-28 shrink-0 space-y-2 rounded-md p-2"
                    style={{
                      backgroundColor: colorsValid ? `${primaryColor}1a` : "transparent",
                    }}
                  >
                    {["Live map", "Trips", "Fleet"].map((label, i) => (
                      <div
                        key={label}
                        className="rounded px-2 py-1.5 text-xs font-medium"
                        style={
                          i === 0
                            ? {
                                backgroundColor: colorsValid
                                  ? primaryColor
                                  : BRAND_DEFAULT.primary_color,
                                color: "#fff",
                              }
                            : { color: "var(--muted-foreground, inherit)" }
                        }
                      >
                        {label}
                      </div>
                    ))}
                  </div>

                  <div className="flex-1 space-y-3">
                    <div className="rounded-md border border-border p-3">
                      <p className="text-xs text-muted-foreground">Today's trips</p>
                      <p className="text-lg font-semibold text-foreground">128</p>
                    </div>
                    <div className="flex gap-2">
                      <span
                        className="inline-flex items-center rounded-md px-3 py-1.5 text-xs font-medium text-white"
                        style={{
                          backgroundColor: colorsValid ? primaryColor : BRAND_DEFAULT.primary_color,
                        }}
                      >
                        Primary button
                      </span>
                      <span
                        className="inline-flex items-center rounded-md px-3 py-1.5 text-xs font-medium"
                        style={{
                          backgroundColor: colorsValid ? accentColor : BRAND_DEFAULT.accent_color,
                          color: "#1a1a1a",
                        }}
                      >
                        Accent button
                      </span>
                      <span
                        className="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium"
                        style={{
                          backgroundColor: colorsValid ? `${accentColor}33` : "transparent",
                          color: colorsValid ? primaryColor : BRAND_DEFAULT.primary_color,
                        }}
                      >
                        Badge
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              <div className="mt-4 flex gap-3 text-xs text-muted-foreground">
                <span className="flex items-center gap-1.5">
                  <span
                    className="h-3 w-3 rounded-full border border-border"
                    style={{ backgroundColor: colorsValid ? primaryColor : BRAND_DEFAULT.primary_color }}
                  />
                  Primary {colorsValid ? primaryColor : "—"}
                </span>
                <span className="flex items-center gap-1.5">
                  <span
                    className="h-3 w-3 rounded-full border border-border"
                    style={{ backgroundColor: colorsValid ? accentColor : BRAND_DEFAULT.accent_color }}
                  />
                  Accent {colorsValid ? accentColor : "—"}
                </span>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      <Modal
        open={resetModalOpen}
        onClose={() => setResetModalOpen(false)}
        title="Reset branding to default?"
        description="This clears the tenant's custom logo and colors and falls back to the platform default brand. This cannot be undone."
        footer={
          <>
            <Button variant="ghost" onClick={() => setResetModalOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleConfirmReset}
              disabled={updateMutation.isPending}
            >
              {updateMutation.isPending ? "Resetting…" : "Reset to default"}
            </Button>
          </>
        }
      />
    </div>
  );
}
