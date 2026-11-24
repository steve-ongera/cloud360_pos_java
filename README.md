# Cloud 360 POS v2 (Java)

A desktop point-of-sale system for small and medium shops in Kenya: pharmacies, supermarkets,
mini-marts, hardware, electronics and boutiques. Built with **Java Swing**, **MySQL** and **Maven**.

It is the simplified single-shop Java version of the Cloud 360 / Medicore POS (Django + React),
with the same business rules: stock held in batches, FEFO selling, an auditable stock ledger,
credit sales with balance collection, and fully audited sale corrections.

## Features

| Screen | What it does |
|---|---|
| **Login** | Email or username + password. Passwords are stored as BCrypt hashes. |
| **Dashboard** | Today's revenue and sales, outstanding balances, low/out-of-stock and expiry alerts, 14-day revenue chart, payment split, top sellers, recent sales, restock list. Auto-refreshes every minute. |
| **New Sale** | Product grid with search and barcode scanning, cart, custom line amounts (negotiated prices), discount, Cash / M-Pesa / Card, live change or balance, printable receipt. |
| **Sales History** | Filter by date, method, cashier, unpaid only or text search. Reprint receipts, collect balance payments, amend sales, view the before/after amendment history. |
| **Products & Stock** | Add, edit and delete products, receive stock by batch, stock-take (set exact quantity), view batches. |

Screens still to come: **Reports**, **Staff**, **Shop Settings** (the menu items exist and show a placeholder).

## Tech stack

- Java 17+
- MySQL 8+
- Maven
- [FlatLaf](https://www.formdev.com/flatlaf/) (modern Swing look and feel)
- jBCrypt (password hashing), Gson (amendment audit JSON), MySQL Connector/J

## Getting started

### 1. Create the database

Run `database/schema.sql` in MySQL Workbench, the IntelliJ Database tool, or the command line:

```bash
mysql -u root -p < database/schema.sql
```

This creates the `cloud360_pos` database and all tables.

If your shop sells goods that expire (medicines, groceries), turn on expiry tracking once:

```sql
UPDATE shop_settings SET tracks_expiry = 1 WHERE id = 1;
```

Leave it off for hardware, clothes and electronics.

### 2. Configure the connection

Edit `src/main/resources/db.properties`:

```properties
db.url=jdbc:mysql://localhost:3306/cloud360_pos?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Africa/Nairobi
db.user=root
db.password=YOUR_MYSQL_PASSWORD
```

A `db.properties` file placed in the folder you start the app from overrides the bundled one,
so a packaged jar never needs the password inside it.

> Do not commit real passwords. Add `src/main/resources/db.properties` to `.gitignore`
> (or commit a `db.properties.example` instead).

### 3. Run

**In IntelliJ IDEA:** open the project, let Maven import the dependencies, then run
`com.cloud360.pos.Main`.

**From the command line:**

```bash
mvn clean package
java -jar target/pos-1.0.0.jar
```

### 4. Sign in

On first start the app creates a default owner account:

| Username | Password |
|---|---|
| `admin` | `admin123` |

**Change this password before real use.**

## Project structure

```
cloud360-pos
├── pom.xml
├── README.md
├── database/
│   └── schema.sql
└── src/main
    ├── java/com/cloud360/pos
    │   ├── Main.java                  entry point
    │   ├── config/Database.java       MySQL connections (reads db.properties)
    │   ├── model/                     plain data classes (User, Product, Sale, CartLine ...)
    │   ├── dao/                       all SQL lives here (UserDao, ProductDao, SaleDao, DashboardDao ...)
    │   ├── ui/                        Swing screens and dialogs
    │   └── util/                      Session, Fmt, HashTool
    └── resources/db.properties
```

Rule of thumb: screens (`ui`) never write SQL, they call a DAO. Every operation that touches more
than one table (sales, amendments, stock-in, stock-take) runs inside one database transaction.

## Roles and permissions

| Menu | Super Admin | Manager | Cashier | Accountant |
|---|:-:|:-:|:-:|:-:|
| Dashboard | yes | yes | yes (own sales) | no |
| New Sale | yes | yes | yes | no |
| Sales History | yes | yes | yes (own sales) | no |
| Products & Stock | yes | yes | no | no |
| Reports | yes | yes | no | yes |
| Staff | yes | no | no | no |
| Shop Settings | yes | no | no | no |

Amending sales: Super Admin and Manager can amend any sale. A Cashier can only amend their own sale
from the same day. These rules are enforced in `SaleDao`, not just hidden in the menu.

## Business rules

- **Stock is derived from batches.** A product's stock is the sum of its live batch quantities.
- **FEFO selling.** Sales take stock from the batch that expires first. Batches without an expiry
  date are used last, oldest first. A line larger than one batch is split across batches.
- **Stock ledger.** Every movement is recorded in `stock_transactions`
  (`STOCK_IN`, `STOCK_OUT`, `ADJUSTMENT`, `EXPIRED`) with who did it.
- **Credit sales.** If the amount paid is lower than the total, the sale carries a balance.
  Balances are collected later from Sales History; each payment is audited.
- **Amendments.** Correcting a sale restores the old stock, re-takes stock for the corrected lines,
  recalculates totals and stores a before/after JSON snapshot with the reason.
- **Soft deletes.** Deleting a product hides it; past sales keep their history.
- **Sale numbers** look like `OTC-20261124-0001` and count up per day.
- **Expiry tracking** is optional per shop (`shop_settings.tracks_expiry`). When off, expiry dates
  and expiry alerts are not required or shown.

## Database overview

| Table | Purpose |
|---|---|
| `users` | Staff accounts and roles |
| `shop_settings` | Single row: shop details, receipt footer, expiry alert days |
| `suppliers` | Suppliers (linked to batches) |
| `products` | Product catalogue |
| `product_batches` | Stock received, with remaining quantity and optional expiry |
| `stock_transactions` | Append-only stock ledger |
| `otc_sales` / `otc_sale_items` | Sale header and line items (one row per batch used) |
| `otc_sale_amendments` | Audit trail of corrections and balance payments |

## Adding staff until the Staff screen exists

1. Generate a password hash:

   ```bash
   java -cp target/pos-1.0.0.jar com.cloud360.pos.util.HashTool "TheirPassword"
   ```

   (In IntelliJ you can run `HashTool` with the password as a program argument.)

2. Insert the user with the printed hash:

   ```sql
   INSERT INTO users (username, email, full_name, password_hash, role)
   VALUES ('mary', 'mary@myshop.co.ke', 'Mary Wanjiku', '<paste hash here>', 'CASHIER');
   ```

   Roles: `SUPER_ADMIN`, `MANAGER`, `CASHIER`, `ACCOUNTANT`.

## Deploying to a shop computer

1. Install Java 17+ and MySQL on the machine (or point `db.url` to a shared MySQL server).
2. Run `database/schema.sql`.
3. Copy `target/pos-1.0.0.jar` and a `db.properties` file into one folder.
4. Start with `java -jar pos-1.0.0.jar` from that folder.

Back up the `cloud360_pos` database regularly:

```bash
mysqldump -u root -p cloud360_pos > backup-2026-11-24.sql
```

## Troubleshooting

| Problem | Fix |
|---|---|
| "Cannot connect to MySQL" on start | Check MySQL is running and `db.properties` has the right URL, user and password. |
| `cannot find symbol` in IntelliJ | A file is in the wrong package or folder. Everything must live under `com.cloud360.pos`. Then run **Build > Rebuild Project**. |
| Red errors after editing `pom.xml` | Click the Maven **Reload** icon. |
| "Batch number already exists" | Batch numbers are unique per product. Leave the field empty to auto-generate one. |
| Invalid login | Use the email or username, not the full name. The default owner is `admin` / `admin123`. |

## Known limitations and roadmap

- Single shop only. The Django version's SaaS layer (tenants, bundles, wallet, M-Pesa STK push) is not part of this version.
- Reports, Staff management and Shop Settings screens are not built yet.
- Receipts print through the system print dialog (plain text layout), not directly to a thermal printer.
- When a sale is amended and a quantity changes, that line is re-priced at the current catalogue price.
- Sale numbers are generated per day by looking at the highest existing number; multiple tills sharing one database would need a counter table.