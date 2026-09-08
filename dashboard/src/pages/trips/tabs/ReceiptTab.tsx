import { useState } from "react";
import { Mail, MessageSquare } from "lucide-react";
import { Button, Input } from "@/components/ui";
import { errorMessage, formatDateTime, formatMoney } from "@/lib/format";
import { useEmailReceiptMutation, useSmsReceiptMutation, type Trip } from "@/hooks/useTrips";
import { PAYMENT_METHOD_LABELS } from "../format";

export interface ReceiptTabProps {
  trip: Trip;
}

/**
 * Trip page Receipt tab (dashboard command-centre plan §7). Neither
 * `TripDetailModal` nor the Trips list ever rendered a receipt preview --
 * the only receipt-facing field either showed was `receipt_ref` -- and the
 * backend has no `GET .../receipt.pdf`-style endpoint that hands the
 * generated PDF's bytes back to a caller (`email_receipt`/`sms_receipt`
 * read the PDF internally via `receipts_service.ensure_receipt_pdf` only to
 * attach/reference it, they don't return it). So this tab shows the real
 * fields a receipt carries (ref, total, GST, payment method, closed-at) as
 * an honest summary rather than an iframe over a PDF the dashboard cannot
 * fetch, plus the two real, previously-unwired resend actions:
 * `POST /v1/trips/{id}/receipt/email` and `/receipt/sms`. Both 409 if the
 * trip isn't closed yet (the fare columns aren't final) -- surfaced as the
 * plain error text the backend sends, not swallowed.
 */
export function ReceiptTab({ trip }: ReceiptTabProps) {
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [emailResult, setEmailResult] = useState<string | null>(null);
  const [smsResult, setSmsResult] = useState<string | null>(null);
  const [emailError, setEmailError] = useState<string | null>(null);
  const [smsError, setSmsError] = useState<string | null>(null);

  const emailMutation = useEmailReceiptMutation();
  const smsMutation = useSmsReceiptMutation();

  const canSend = trip.status === "closed";

  async function handleEmail() {
    setEmailError(null);
    setEmailResult(null);
    if (!email.trim()) {
      setEmailError("Enter an email address first.");
      return;
    }
    try {
      const res = await emailMutation.mutateAsync({ tripId: trip.id, toEmail: email.trim() });
      setEmailResult(
        res.mock
          ? `Mock send -- would have emailed ${res.would_send_to ?? email.trim()}.`
          : `Sent to ${res.to_email ?? email.trim()}.`,
      );
    } catch (err) {
      setEmailError(errorMessage(err));
    }
  }

  async function handleSms() {
    setSmsError(null);
    setSmsResult(null);
    if (!phone.trim()) {
      setSmsError("Enter a phone number first.");
      return;
    }
    try {
      const res = await smsMutation.mutateAsync({ tripId: trip.id, toPhone: phone.trim() });
      setSmsResult(
        res.mock
          ? `Mock send -- would have texted ${res.would_send_to ?? phone.trim()}.`
          : `Sent to ${res.to_phone ?? phone.trim()}.`,
      );
    } catch (err) {
      setSmsError(errorMessage(err));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-border p-3">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Receipt summary
        </p>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
          <div>
            <dt className="text-xs text-muted-foreground">Receipt ref</dt>
            <dd className="font-medium text-foreground">{trip.receipt_ref ?? "—"}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">Total</dt>
            <dd className="font-medium text-foreground">
              {trip.status === "closed" ? formatMoney(trip.total) : "—"}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">GST component</dt>
            <dd className="font-medium text-foreground">
              {trip.status === "closed" ? formatMoney(trip.gst_component) : "—"}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">Payment method</dt>
            <dd className="font-medium text-foreground">{PAYMENT_METHOD_LABELS[trip.payment_method]}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">Closed</dt>
            <dd className="font-medium text-foreground">{formatDateTime(trip.end_at)}</dd>
          </div>
        </dl>
        {!canSend && (
          <p className="mt-2 text-xs text-muted-foreground">
            This trip is still open -- the receipt PDF only exists once it's closed.
          </p>
        )}
        <p className="mt-2 text-xs text-muted-foreground">
          No endpoint returns the generated receipt PDF's bytes to the dashboard for an inline
          preview -- only email/sms delivery of the PDF the server already generated.
        </p>
      </div>

      <div className="rounded-lg border border-border p-3">
        <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          <Mail className="h-3.5 w-3.5" /> Resend by email
        </p>
        <div className="flex gap-2">
          <Input
            placeholder="passenger@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={!canSend}
          />
          <Button onClick={handleEmail} disabled={!canSend || emailMutation.isPending}>
            {emailMutation.isPending ? "Sending…" : "Send"}
          </Button>
        </div>
        {emailResult && <p className="mt-2 text-sm text-success">{emailResult}</p>}
        {emailError && <p className="mt-2 text-sm text-destructive">{emailError}</p>}
      </div>

      <div className="rounded-lg border border-border p-3">
        <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          <MessageSquare className="h-3.5 w-3.5" /> Resend by SMS
        </p>
        <div className="flex gap-2">
          <Input
            placeholder="04xx xxx xxx"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            disabled={!canSend}
          />
          <Button onClick={handleSms} disabled={!canSend || smsMutation.isPending}>
            {smsMutation.isPending ? "Sending…" : "Send"}
          </Button>
        </div>
        {smsResult && <p className="mt-2 text-sm text-success">{smsResult}</p>}
        {smsError && <p className="mt-2 text-sm text-destructive">{smsError}</p>}
      </div>
    </div>
  );
}
