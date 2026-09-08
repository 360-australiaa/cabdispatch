import { Monitor, Moon, Sun } from "lucide-react";
import { useTheme, type ThemePreference } from "@/lib/theme";
import { cn } from "@/lib/utils";

const OPTIONS: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
  { value: "system", label: "System", icon: Monitor },
];

export interface ThemeToggleProps {
  className?: string;
}

/**
 * Three-way theme control: Light / Dark / System.
 *
 * Three states rather than a two-state switch, because "System" is a real and
 * distinct choice -- it is the difference between "I want dark" and "I want
 * whatever this machine is set to", and collapsing them means an operator who
 * picks dark at night is stuck in dark on the morning shift.
 *
 * Rendered as a `radiogroup`: these are mutually exclusive options with a
 * persistent selection, which is what radio semantics describe. A row of
 * buttons would announce three unrelated actions with no indication of which
 * one is in effect.
 */
export function ThemeToggle({ className }: ThemeToggleProps) {
  const { preference, setPreference } = useTheme();

  return (
    <div
      role="radiogroup"
      aria-label="Colour theme"
      className={cn("inline-flex rounded-md border border-white/15 bg-white/5 p-0.5", className)}
    >
      {OPTIONS.map(({ value, label, icon: Icon }) => {
        const selected = preference === value;
        return (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={label}
            title={label}
            onClick={() => setPreference(value)}
            className={cn(
              "inline-flex h-7 w-7 items-center justify-center rounded-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-accent",
              selected
                ? "bg-white/20 text-white"
                : "text-white/60 hover:bg-white/10 hover:text-white",
            )}
          >
            <Icon className="h-4 w-4" aria-hidden="true" />
          </button>
        );
      })}
    </div>
  );
}
