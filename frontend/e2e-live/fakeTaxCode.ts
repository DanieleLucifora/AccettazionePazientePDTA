const ODD = [1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23];

// Codice fiscale formalmente valido ma fittizio, univoco per esecuzione (suffisso numerico variabile).
export function fakeTaxCode(): string {
  const serial = String(Date.now() % 1000).padStart(3, '0');
  const base = `ZZZTST80A01Z${serial}`.slice(0, 15);
  let sum = 0;
  [...base].forEach((char, i) => {
    const index = /\d/.test(char) ? Number(char) : char.charCodeAt(0) - 65;
    sum += i % 2 === 0 ? ODD[index] : index;
  });
  return base + String.fromCharCode(65 + (sum % 26));
}
