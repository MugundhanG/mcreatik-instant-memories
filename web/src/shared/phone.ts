/** The studio's contact number on galleries and print: links, display format and icons. */

/** "+919876543210" → "+91 98765 43210"; other numbers are shown as saved. */
export function formatPhone(phone: string): string {
  const m = /^\+91(\d{5})(\d{5})$/.exec(phone)
  return m ? `+91 ${m[1]} ${m[2]}` : phone
}

/** Opens WhatsApp to this number with a message ready to send. */
export const waLink = (phone: string, text: string) => `https://wa.me/${phone.replace(/\D/g, '')}?text=${encodeURIComponent(text)}`

export const telLink = (phone: string) => `tel:${phone.replace(/[^\d+]/g, '')}`
