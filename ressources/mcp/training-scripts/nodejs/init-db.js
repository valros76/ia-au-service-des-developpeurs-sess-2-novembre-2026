import { DatabaseSync } from 'node:sqlite';

const db = new DatabaseSync('entreprise.db');

db.exec(`
  DROP TABLE IF EXISTS commandes;
  DROP TABLE IF EXISTS produits;
  DROP TABLE IF EXISTS clients;

  CREATE TABLE clients (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nom TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    segment TEXT NOT NULL
  );

  CREATE TABLE produits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nom TEXT NOT NULL,
    prix REAL NOT NULL,
    stock INTEGER NOT NULL
  );

  CREATE TABLE commandes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id INTEGER NOT NULL REFERENCES clients(id),
    produit_id INTEGER NOT NULL REFERENCES produits(id),
    quantite INTEGER NOT NULL,
    statut TEXT NOT NULL,
    date_commande TEXT NOT NULL
  );

  INSERT INTO clients (nom, email, segment) VALUES
    ('Acme Corp', 'contact@acme.fr', 'B2B'),
    ('TechNova', 'achats@technova.io', 'B2B'),
    ('Claire Martin', 'claire.m@gmail.com', 'B2C'),
    ('Lucas Bernard', 'l.bernard@outlook.fr', 'B2C'),
    ('Studio Pixel', 'admin@studiopixel.fr', 'B2B');

  INSERT INTO produits (nom, prix, stock) VALUES
    ('Clavier Mécanique Pro', 129.90, 42),
    ('Écran 27" 4K', 399.00, 15),
    ('Dock USB-C', 89.50, -3),       -- Anomalie volontaire : stock négatif
    ('Souris Ergonomique', 69.00, 28),
    ('Webcam HD', 54.00, -1);        -- Anomalie volontaire : stock négatif

  INSERT INTO commandes (client_id, produit_id, quantite, statut, date_commande) VALUES
    (1, 1, 5, 'LIVREE', '2026-10-01'),
    (2, 2, 2, 'LIVREE', '2026-10-05'),
    (3, 4, 1, 'EXPEDIEE', '2026-10-12'),
    (1, 3, 10, 'BLOQUEE', '2026-10-15'), -- Anomalie volontaire : commande bloquée
    (4, 1, 1, 'LIVREE', '2026-10-18'),
    (5, 5, 4, 'BLOQUEE', '2026-10-20'),  -- Anomalie volontaire : commande bloquée
    (2, 4, 3, 'EN_COURS', '2026-11-02'),
    (3, 2, 1, 'LIVREE', '2026-11-10');
`);

console.log('Base entreprise.db générée avec succès.');