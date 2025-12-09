import socket
import threading
import time
from ClassPrincipale import Simulation

HOST = "127.0.0.1"
PORT = 5001

# Variables globales pour la simulation actuelle
sim = None
lock = threading.Lock()  # Lock pour protéger l'accès à l'objet 'sim' lui-même


# Envoie les dimensions de la grille au client lors de la connexion ou du changement de scénario.
# Paramètres :
#   - conn (socket) : La connexion client.
#   - simulation (Simulation) : L'objet simulation actuel.
def send_config(conn, simulation):
    """Envoie la configuration de la grille au client."""
    if simulation and simulation.ville:
        # Format: CONFIG;Largeur;Hauteur
        msg = f"CONFIG;{simulation.ville.largeur};{simulation.ville.hauteur}\n"
        try:
            conn.sendall(msg.encode())
        except Exception as e:
            print(f"Erreur d'envoi CONFIG: {e}")


# Gère un client unique après acceptation de la connexion. Crée un thread pour l'écoute des commandes.
# Paramètres :
#   - conn (socket) : La connexion client.
def client_handler(conn):
    """Gère un client connecté (écoute des commandes et envoi des données)."""
    global sim
    print("Client connecté.")

    # Envoi config initiale
    with lock:
        if sim: send_config(conn, sim)

    # Thread d'écoute des commandes (SCENARIO, STOP, SPEED)
    def listen():
        global sim
        while True:
            try:
                data = conn.recv(1024).decode()
                if not data: break  # Déconnexion normale

                parts = data.strip().split(":")
                cmd = parts[0]

                # Protège l'accès à l'objet simulation globale (démarrage/arrêt/changement)
                with lock:
                    if cmd == "SCENARIO":
                        if sim: sim.arreter()
                        sim = Simulation(parts[1])
                        sim.demarrer()
                        send_config(conn, sim)  # Renvoyer la config si le scénario change
                    elif cmd == "STOP":
                        if sim: sim.arreter()
                    elif cmd == "SPEED":
                        if sim: sim.set_vitesse(parts[1])
            except Exception as e:
                # Le client s'est déconnecté ou a planté
                print(f"Erreur/Déconnexion client (écoute): {e}")
                break

    # Démarre l'écoute des commandes en arrière-plan
    threading.Thread(target=listen, daemon=True).start()

    # Boucle d'envoi des données (taux de rafraîchissement)
    while True:
        try:
            lignes = []
            # Protège l'accès aux données de la simulation pour l'envoi
            with lock:
                if sim and sim.running:
                    # 1. ENVOI DONNÉES LIEUX
                    # Lieux: TYPE;X;Y;OCCUPE(0/1)
                    for m in sim.ville.maisons: lignes.append(f"MAISON;{m[0]};{m[1]};0")
                    for l in sim.ville.tous_lieux:
                        t = "PARC" if l['symbole'] == 'P' else ("TRAVAIL" if l['symbole'] == 'T' else "LOISIR")
                        for p in l['places']:
                            occ = "1" if p["occupant"] else "0"
                            lignes.append(f"{t};{p['x']};{p['y']};{occ}")

                    # 2. ENVOI DONNÉES AGENTS
                    # AGENT;Nom;X;Y;Energie;Stress;Argent;Etat;Angle
                    for a in sim.agents:
                        lignes.append(
                            f"AGENT;{a.nom};{a.x:.2f};{a.y:.2f};{a.energie:.1f};{a.stress:.1f};{a.argent:.1f};{a.etat};{a.angle_vue:.2f}"
                        )

                    # 3. ENVOI DES STATISTIQUES (SI DISPONIBLES)
                    # STATS;MoyNrj;MoyStress;MoyArg;NbVivants;NbMorts;NbOccupes
                    if sim.stats_aggregator.stats_log:
                        ls = sim.stats_aggregator.stats_log[-1]
                        lignes.append(
                            f"STATS;{ls['moyenne_energie']};{ls['moyenne_stress']};{ls['moyenne_argent']};{ls['agents_vivants']};{ls['agents_morts']};{ls['agents_occupes']}"
                        )

            # Envoi de la trame complète si des données sont disponibles
            if lignes:
                # Envoi global + marqueur de fin de trame
                msg = "\n".join(lignes) + "\nEND\n"
                conn.sendall(msg.encode())

            time.sleep(0.05)  # ~20 FPS pour le client

        except (BrokenPipeError, ConnectionResetError):
            print("Client déconnecté (envoi).")
            break
        except Exception as e:
            print(f"Erreur boucle d'envoi: {e}")
            break


# Fonction principale qui initialise la simulation et démarre le serveur d'écoute des connexions.
def main():
    """Initialise la simulation et le serveur."""
    global sim

    # Démarrage automatique du scénario 1 pour une exécution immédiate
    sim = Simulation("1")
    sim.demarrer()
    print("Serveur lancé sur 5001 (Simu S1 auto).")

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)  # Réutiliser le port immédiatement
    s.bind((HOST, PORT))
    s.listen()

    # Boucle d'acceptation de nouvelles connexions
    while True:
        try:
            conn, addr = s.accept()
            # Gérer le client dans un nouveau thread pour le non-blocage
            threading.Thread(target=client_handler, args=(conn,), daemon=True).start()
        except KeyboardInterrupt:
            print("\nArrêt du serveur.")
            if sim: sim.arreter()
            s.close()
            break
        except Exception as e:
            print(f"Erreur d'acceptation de connexion: {e}")
            time.sleep(1)


if __name__ == "__main__":
    main()