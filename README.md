# SmartBank-backend

Module Java 17/Maven contenant les éléments métier et de persistance déplacés depuis SmartBank-App.

## Structure

```text
src/main/java/ma/bankdirect/
  model/       Clients, utilisateurs, comptes, transactions et enums
  exception/   Exceptions métier et d’accès aux données
  util/        Gestion des montants avec BigDecimal
  config/      Chargement de la configuration
  db/          Fournisseur de connexions JDBC
  TestApp.java Vérifications manuelles historiques en console
src/main/resources/
  app.properties Configuration de démonstration
  sql/schema.sql Schéma MySQL
  sql/seed.sql   Données de démonstration
```

## Compiler

```bash
mvn clean verify
```

Le connecteur MySQL est une dépendance de ce module uniquement. Pour utiliser la base, adapter la configuration puis importer `schema.sql` et `seed.sql` sur une base de développement dédiée. Ces scripts ne sont pas exécutés automatiquement par la compilation ou par le lancement du frontend.

## État actuel

Ce module n’est pas encore un serveur API. Les DAO, services d’authentification, sessions, hachage des mots de passe et mécanismes de récupération sont à développer. `TestApp` est un programme manuel, pas une suite de tests automatisés.

Le schéma et les données SQL existants ont été conservés sans changement fonctionnel. Ils contiennent des comptes et mots de passe de démonstration ; ils doivent évoluer avant toute authentification réelle. Le frontend JavaFX n’embarque pas cette configuration et ne se connecte pas directement à MySQL.
