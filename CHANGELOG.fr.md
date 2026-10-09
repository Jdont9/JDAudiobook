# Changelog

🇬🇧 [In English](CHANGELOG.md)

## 1.2.1
**Corrections**
- La rotation de l'écran ne ferme plus le lecteur (livre ouvert, écran lecteur/statistiques, recherche et tri sont conservés ; le livre est rechargé si le processus a été tué).
- Android Auto ne se fige plus quand le chargement de la bibliothèque échoue : la requête se termine par une erreur au lieu d'attendre indéfiniment.
- Les pochettes ne sont plus recopiées dans chaque fichier de la playlist d'un livre (300 fichiers = 300 copies) ; elles sont servies par URI, ce qui allège aussi la notification, l'écran de verrouillage et Android Auto.
- Le retour en arrière automatique à la reprise ne s'applique plus si tu as déplacé la position toi-même pendant la pause (choix d'un chapitre, par exemple).
- La barre de progression ne déplace la lecture qu'au relâchement, et non à chaque mouvement du doigt.
- Le bouton « saut des silences » reflète l'état réel quand on rouvre le lecteur.
- L'ouverture d'un livre n'attend plus indéfiniment si le service de lecture ne démarre pas (message d'erreur au bout de 10 s).
- L'autorisation des notifications n'est demandée que si elle n'a pas déjà été accordée.

**Nouveautés**
- **Signets** par livre.
- Réglage de la **durée du saut** (10, 15, 30, 45 ou 60 s), aussi utilisée par la notification, l'écran de verrouillage et Android Auto.
- Minuterie de sommeil : option **fin du fichier en cours** et **fondu sonore** de 15 s avant l'arrêt.
- Champ de **recherche** et tri par **écoute récente** dans la bibliothèque.
- Un livre est marqué **lu** automatiquement quand son dernier fichier se termine (position remise au début).
- Le widget affiche la pochette et reprend le dernier livre quand le lecteur est vide.
- Un livre dont la pochette est introuvable n'est pas recherché à nouveau pendant 7 jours.

**Performances**
- Le cache de la bibliothèque passe des préférences à un fichier, est gardé en mémoire et n'est plus lu sur le thread principal au démarrage.
- Le scan n'essaie plus d'ouvrir un fichier Smart AudioBook Player « deviné » pour chaque livre (seulement pour les premiers, tant qu'aucun n'est trouvé) ; le code de diagnostic laissé en place a été retiré.
- `position.jd.json` est écrit toutes les 60 s au lieu de 20 s en lecture (toujours à la pause et au changement de fichier), pour ménager les dossiers synchronisés.

**Divers**
- Téléchargement des pochettes plafonné à 10 Mo.
- La sauvegarde Android ne couvre plus que la progression, les signets et les statistiques.
- Tests unitaires des fonctions utilitaires (ordre naturel, noms de fichiers, correspondance des pochettes), exécutés par le workflow ; Dependabot activé pour les dépendances et les actions GitHub.

## 1.2.0
- **Nouveau** : les actions de la bibliothèque sont dans un menu derrière une icône engrenage (affiché seulement à la demande).
- **Nouveau** : fenêtre « À propos » avec le numéro de version et une vérification de mise à jour à la demande (jamais automatique).

## 1.1.0
- **Nouveau** : l'appli est disponible en français et en anglais (selon la langue du téléphone ; choix de la langue par appli sur Android 13+).
- **Licence** : le projet est désormais publié sous GPL-3.0-or-later.
- Documentation (README, guide de publication, changelog) en français et en anglais ; mention de la réalisation par IA et lien de don ajoutés.

## 1.0.0
Première version signée.

- Bibliothèque par dossier, lecteur (vitesse, minuterie de sommeil, saut des silences, gain de volume, chapitres m4b/m4a), widget, accès depuis Android Auto.
- Progression sauvegardée dans `position.jd.json` à côté des fichiers audio ; reprise de Smart AudioBook Player.
- Statistiques d'écoute.
- Mini-lecteur en bas de la bibliothèque quand un livre est chargé.
- Le geste ou la touche retour du système revient à la bibliothèque (la lecture continue) au lieu de fermer l'appli.
- Recherche et téléchargement des pochettes manquantes (iTunes, Audible, Audiolib, Google Books, Open Library) et ajout d'une pochette par lien collé.
