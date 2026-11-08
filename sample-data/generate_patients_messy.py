"""Generates patients_messy.csv: 5,000 fictional patients for the CSV import demo.

Exactly 250 rows (5%) are broken on purpose, one problem each, so a fresh import ends with
4,750 imported and 250 rejected rows. The other rows are valid but messy, the way real exports
are: an Excel BOM, CRLF line endings, "friendly" header names, stray whitespace, mixed-case
emails, several date / phone / gender / blood-type spellings, quoted fields and blank lines.

All data is fictional: emails use the reserved example.* domains and phone numbers come from
the UK range set aside for drama (07700 900000-900999).

Usage:  python sample-data/generate_patients_messy.py   (output is deterministic)
"""

import csv
import io
import random
import re
import unicodedata
from datetime import date, timedelta
from pathlib import Path

ROWS = 5000
SEED = 20261108
HERE = Path(__file__).resolve().parent
SEED_MIGRATION = HERE.parent / "backend/src/main/resources/db/migration/V5__seed_patients.sql"

# One problem per broken row; the counts add up to 250 (5% of 5,000).
BROKEN = {
    "bad_email": 60,
    "bad_date": 50,
    "missing_name": 50,
    "duplicate_in_file": 30,
    "duplicate_of_seeded": 20,
    "bad_gender": 15,
    "bad_blood_type": 10,
    "wrong_column_count": 15,
}

FEMALE = ["Aisha", "Amelia", "Anna", "Ava", "Chloe", "Clara", "Elena", "Emily", "Eva", "Fatima", "Freya", "Grace",
          "Hannah", "Isla", "Leila", "Lucy", "Mariam", "Mia", "Nadia", "Olivia", "Priya", "Sofia", "Zara", "Zoë",
          "Siobhán", "Ines"]
MALE = ["Adam", "Ali", "Ben", "Daniel", "David", "Ethan", "Hamza", "Harry", "Isaac", "Jack", "James", "Kwame",
        "Leo", "Liam", "Lucas", "Mohamed", "Noah", "Oliver", "Omar", "Rhys", "Samuel", "Thomas", "Yusuf", "José"]
OTHER = ["Alex", "Jordan", "Kai", "Morgan", "Robin", "Sam"]
LAST = ["Andersson", "Bernard", "Brown", "Costa", "Davies", "El Amrani", "Evans", "Fernandez", "Ferreira", "Fischer",
        "Haddad", "Hughes", "Jones", "Khan", "Kowalski", "Mensah", "Müller", "Nguyen", "O'Brien", "Okafor", "Patel",
        "Rahman", "Romano", "Rossi", "Schmidt", "Silva", "Singh", "Smith", "Taylor", "Walker", "Weber", "Williams",
        "Wilson", "Yilmaz", "de Vries", "MacDonald"]
CITIES = ["London", "London", "London", "Manchester", "Birmingham", "Leeds", "Bristol", "Edinburgh", "Glasgow",
          "Cardiff", "Liverpool", "Sheffield", "Oxford", "Cambridge", "Newcastle upon Tyne", "Stoke-on-Trent",
          "Belfast", "Nottingham", "Brighton"]
BLOOD = [("O+", 35), ("A+", 30), ("B+", 8), ("O-", 7), ("A-", 7), ("AB+", 3), ("B-", 2), ("AB-", 1), ("", 7)]
DOMAINS = ["example.com", "example.org", "example.net"]


def main() -> None:
    rng = random.Random(SEED)
    seeded_emails = re.findall(r"'([^']+@example\.[a-z]+)'", SEED_MIGRATION.read_text(encoding="utf-8"))

    rows = [valid_row(rng, index) for index in range(ROWS)]

    # Choose which rows to break, never touching row 0..99 so in-file duplicates have an original.
    kinds = [kind for kind, count in BROKEN.items() for _ in range(count)]
    targets = rng.sample(range(100, ROWS), len(kinds))
    broken = set(targets)
    lines = {}
    for kind, index in zip(kinds, targets):
        originals = [i for i in range(index) if i not in broken]
        lines[index] = break_row(rng, kind, rows[index], rows[rng.choice(originals)], seeded_emails)

    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\r\n")
    out.write("﻿")  # Excel's UTF-8 marker
    writer.writerow(["First Name", "Last Name", "DOB", "Sex", "Email Address", "Phone", "City", "Blood Type"])
    for index, row in enumerate(rows):
        if index in lines:
            out.write(lines[index] + "\r\n")
        else:
            writer.writerow(row)
        if index % 1250 == 1249:
            out.write("\r\n")  # stray blank line, ignored by the import

    target = HERE / "patients_messy.csv"
    target.write_text(out.getvalue(), encoding="utf-8", newline="")
    print(f"Wrote {target} ({ROWS} rows, {sum(BROKEN.values())} broken)")


def valid_row(rng: random.Random, index: int) -> list:
    gender = rng.choices(["F", "M", "O"], weights=[50, 47, 3])[0]
    first = rng.choice({"F": FEMALE, "M": MALE, "O": OTHER}[gender])
    last = rng.choice(LAST)
    born = date(1935, 1, 1) + timedelta(days=rng.randrange((date(2020, 12, 31) - date(1935, 1, 1)).days))
    # The number keeps every email unique and distinct from the seeded patients (first.last@...).
    email = f"{slug(first)}.{slug(last)}.{index + 1000}@{rng.choice(DOMAINS)}"
    phone = messy_phone(rng, 900000 + rng.randrange(1000)) if rng.random() < 0.85 else ""
    blood = rng.choices([b for b, _ in BLOOD], weights=[w for _, w in BLOOD])[0]
    return [
        pad(rng, first),
        pad(rng, last),
        messy_date(rng, born),
        messy_gender(rng, gender),
        messy_case(rng, email),
        phone,
        pad(rng, rng.choice(CITIES)),
        messy_blood(rng, blood),
    ]


def break_row(rng, kind, row, original, seeded_emails) -> str:
    row = list(row)
    if kind == "bad_email":
        local = row[4].split("@")[0]
        row[4] = rng.choice([local, local + "@", local + "@example", "@example.com", local + "@@example.com",
                             local + "(at)example.com", local + "@example..com"])
    elif kind == "bad_date":
        row[2] = rng.choice(["31/02/1990", "1990-13-01", "29/02/2023", "unknown", "N/A", "12/31/1985",
                             "2031-06-15", "1850-01-01", "00/00/0000", "32.01.1975"])
    elif kind == "missing_name":
        row[rng.choice([0, 1])] = rng.choice(["", "   "])
    elif kind == "duplicate_in_file":
        row[4] = original[4].strip().upper() if rng.random() < 0.5 else " " + original[4].strip()
    elif kind == "duplicate_of_seeded":
        row[4] = messy_case(rng, rng.choice(seeded_emails))
    elif kind == "bad_gender":
        row[3] = rng.choice(["unknown", "?", "N", "Femail", "2"])
    elif kind == "bad_blood_type":
        row[7] = rng.choice(["C+", "AB", "Rh+", "A positive?", "O++"])
    elif kind == "wrong_column_count":
        if rng.random() < 0.5:
            del row[5]  # a field is missing
        else:
            row.append("extra")  # a trailing extra field
    return to_csv_line(row)


def to_csv_line(row: list) -> str:
    buffer = io.StringIO()
    csv.writer(buffer, lineterminator="").writerow(row)
    return buffer.getvalue()


def slug(name: str) -> str:
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z]", "", ascii_name.lower())


def pad(rng, value: str) -> str:
    roll = rng.random()
    if roll < 0.05:
        return "  " + value
    if roll < 0.10:
        return value + "   "
    return value


def messy_date(rng, born: date) -> str:
    style = rng.choices(range(7), weights=[50, 20, 8, 8, 6, 4, 4])[0]
    return [
        born.isoformat(),
        born.strftime("%d/%m/%Y"),
        f"{born.day}/{born.month}/{born.year}",
        born.strftime("%d.%m.%Y"),
        born.strftime("%d %b %Y"),
        born.strftime("%B %d, %Y").replace(" 0", " "),
        born.isoformat() + "T00:00:00",
    ][style]


def messy_gender(rng, gender: str) -> str:
    return rng.choice({"F": ["F", "F", "f", "Female", "female", "FEMALE"],
                       "M": ["M", "M", "m", "Male", "male", "MALE"],
                       "O": ["O", "Other", "other", "X", "non-binary"]}[gender])


def messy_case(rng, email: str) -> str:
    roll = rng.random()
    if roll < 0.08:
        return email.upper()
    if roll < 0.20:
        local, domain = email.split("@")
        return local.title() + "@" + domain.capitalize()
    if roll < 0.25:
        return " " + email + " "
    return email


def messy_phone(rng, number: int) -> str:
    digits = str(number)  # 900123
    return rng.choice([
        f"07700 {digits}",
        f"07700{digits}",
        f"+44 7700 {digits}",
        f"+447700{digits}",
        f"(07700) {digits[:3]}-{digits[3:]}",
        f"0044 7700 {digits}",
        f"07700.{digits[:3]}.{digits[3:]}",
    ])


def messy_blood(rng, blood: str) -> str:
    if not blood:
        return ""
    group, sign = blood[:-1], blood[-1]
    word = "positive" if sign == "+" else "negative"
    return rng.choices([
        blood,
        blood.lower(),
        f"{group} {'pos' if sign == '+' else 'neg'}",
        f"{group} {word}",
        blood.replace("O", "0"),
        f"{group}_{word.upper()}",
    ], weights=[60, 10, 10, 8, 6, 6])[0]


if __name__ == "__main__":
    main()
