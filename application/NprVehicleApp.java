package org.eclipse.mosaic.app.npr;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.mosaic.fed.application.ambassador.simulation.VehicleParameters;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.app.api.VehicleApplication;
import org.eclipse.mosaic.fed.application.app.api.os.VehicleOperatingSystem;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;
import org.eclipse.mosaic.lib.geo.GeoPoint;
import org.eclipse.mosaic.lib.objects.vehicle.VehicleData;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.util.scheduling.Event;

public class NprVehicleApp extends AbstractApplication<VehicleOperatingSystem>
        implements VehicleApplication, CommunicationApplication {

    public enum Personalidade {
        COOPERANTE,
        PADRAO,
        POUCO_COOPERANTE,
        NAO_COOPERANTE
    }

    private Personalidade minhaPersonalidade;

    // CAM
    private static final long INTERVALO_CAM = 1_000_000_000L;
    private long proximoCamTempo = 0;

    // Estado da decisão do condutor
    private boolean jaDecidiuOQueFazer = false;
    private boolean decidiuObedecer = false;
    private double ultimaDistanciaAoEvento = -1.0;

    // Multi-hop
    private boolean jaRetransmitiu = false;
    private boolean aEsperaDeRetransmitir = false;
    private long tempoAgendadoParaRetransmitir = 0L;
    private NprDenmMessage mensagemGuardada = null;
    private String eventIdAgendado = null;

    // Para não reagir múltiplas vezes ao mesmo evento
    private final Set<String> eventosRecebidos = new HashSet<>();
    private final Set<String> eventosSuprimidos = new HashSet<>();

    // Fórmula do backoff
    private static final double RADIO_RANGE = 300.0;       // metros
    private static final long T_MAX_ESPERA = 100_000_000L; // 100 ms
    private static final long JITTER_MAX = 10_000_000L;    // 10 ms

    // Velocidade "normal"
    private static final double VELOCIDADE_CRUZEIRO = 33.33; // 120 km/h ~ ajustar conforme cenário

    @Override
    public void onStartup() {
        atribuirPersonalidade();
        pintarCarro();

        getOs().getAdHocModule().enable();

        System.out.println(String.format(
                "[START] Veículo: %-8s | Personalidade: %-16s | Rádio: %s",
                getOs().getId(),
                minhaPersonalidade.name(),
                getOs().getAdHocModule().isEnabled() ? "OK" : "ERRO"
        ));

        proximoCamTempo = getOs().getSimulationTime() + INTERVALO_CAM;
        getOs().getEventManager().addEvent(proximoCamTempo, this);
    }

    @Override
    public void processEvent(Event event) throws Exception {
        long tempoAtual = getOs().getSimulationTime();

        // envio periódico de CAM
        if (tempoAtual >= proximoCamTempo) {
            getOs().getAdHocModule().sendCam();
            proximoCamTempo = tempoAtual + INTERVALO_CAM;
            getOs().getEventManager().addEvent(proximoCamTempo, this);
        }

        // retransmissão do DENM
        if (aEsperaDeRetransmitir && tempoAtual >= tempoAgendadoParaRetransmitir && mensagemGuardada != null) {
            MessageRouting routing = getOs().getAdHocModule()
                    .createMessageRouting()
                    .topoBroadCast(1);

            NprDenmMessage retransmitida = new NprDenmMessage(
                    routing,
                    mensagemGuardada.getVelocidadeRecomendada(),
                    mensagemGuardada.getTempoExpiracao(),
                    mensagemGuardada.getEventId()
            );

            getOs().getAdHocModule().sendV2xMessage(retransmitida);

            aEsperaDeRetransmitir = false;
            jaRetransmitiu = true;
            eventosRecebidos.add(mensagemGuardada.getEventId());

            System.out.println(String.format(
                    "📡 %-8s RETRANSMITIU alerta %s",
                    getOs().getId(),
                    mensagemGuardada.getEventId()
            ));
        }
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        V2xMessage msg = receivedV2xMessage.getMessage();

        if (!(msg instanceof NprDenmMessage)) {
            return;
        }

        NprDenmMessage denm = (NprDenmMessage) msg;
        long agora = getOs().getSimulationTime();

        // TTL
        if (agora > denm.getTempoExpiracao()) {
            return;
        }

        GeoPoint minhaPos = getOs().getNavigationModule().getCurrentPosition();
        GeoPoint emissorPos = msg.getRouting().getSource().getSourcePosition();

        double distanciaAoEmissor = minhaPos.distanceTo(emissorPos);
        String nomeEmissor = msg.getRouting().getSource().getSourceName();
        String eventId = denm.getEventId();

        // SUPRESSÃO:
        // se eu já tinha agendado retransmitir este mesmo evento e ouvi outro nó a fazê-lo, calo-me
        if (aEsperaDeRetransmitir && eventId.equals(eventIdAgendado) && !eventosSuprimidos.contains(eventId)) {
            aEsperaDeRetransmitir = false;
            eventosSuprimidos.add(eventId);

            System.out.println(String.format(
                    "%-8s SUPRIMIU retransmissão do evento %s (ouviu %s)",
                    getOs().getId(),
                    eventId,
                    nomeEmissor
            ));
        }
        // AGENDAMENTO:
        else if (!jaRetransmitiu && !eventosRecebidos.contains(eventId)) {
            double d = Math.min(distanciaAoEmissor, RADIO_RANGE);
            long tEspera = (long) (T_MAX_ESPERA * (1.0 - (d / RADIO_RANGE)));
            tEspera += (long) (getRandom().nextDouble() * JITTER_MAX);

            tempoAgendadoParaRetransmitir = agora + tEspera;
            mensagemGuardada = denm;
            eventIdAgendado = eventId;
            aEsperaDeRetransmitir = true;

            getOs().getEventManager().addEvent(tempoAgendadoParaRetransmitir, this);

            System.out.println(String.format(
                    "%-8s agendou retransmissão do evento %s em %.2f ms",
                    getOs().getId(),
                    eventId,
                    tEspera / 1_000_000.0
            ));
        }

        // distância ao evento: aqui vocês estão a usar a posição do emissor da mensagem como referência
        double distanciaAoEvento = distanciaAoEmissor;

        // GEOCASTING DIRECIONAL:
        // se já estamos a afastar-nos do evento, deixamos de obedecer e retomamos velocidade
        if (ultimaDistanciaAoEvento != -1.0 && distanciaAoEvento > ultimaDistanciaAoEvento) {
            if (decidiuObedecer) {
                getOs().changeSpeedWithPleasantAcceleration(VELOCIDADE_CRUZEIRO);
                System.out.println(String.format(
                        "%-8s já passou a obra. Retomando velocidade normal. Dist=%.1f m",
                        getOs().getId(),
                        distanciaAoEvento
                ));
                decidiuObedecer = false;
            }
            ultimaDistanciaAoEvento = distanciaAoEvento;
            return;
        }

        ultimaDistanciaAoEvento = distanciaAoEvento;

        // decisão não determinística
        if (!jaDecidiuOQueFazer && distanciaAoEvento <= 1000.0) {
            double sorteio = getRandom().nextDouble();
            double probObedecer;

            switch (minhaPersonalidade) {
                case COOPERANTE:
                    probObedecer = 1.0;
                    break;
                case PADRAO:
                    probObedecer = 0.5;
                    break;
                case POUCO_COOPERANTE:
                    probObedecer = 0.25;
                    break;
                default:
                    probObedecer = 0.0;
                    break;
            }

            decidiuObedecer = (sorteio < probObedecer);
            jaDecidiuOQueFazer = true;
        }

        if (jaDecidiuOQueFazer && decidiuObedecer) {
            double velocidadeAlvo;
            String zona;

            if (distanciaAoEvento > 500.0) {
                velocidadeAlvo = 19.44; // 70 km/h
                zona = "ZONA ALERTA";
            } else if (distanciaAoEvento > 200.0) {
                velocidadeAlvo = 13.89; // 50 km/h
                zona = "ZONA APROXIMAÇÃO";
            } else {
                velocidadeAlvo = 8.33;  // 30 km/h
                zona = "ZONA CRÍTICA";
            }

            aplicarTravagemPorPersonalidade(velocidadeAlvo, distanciaAoEvento, zona);
        }
    }

    private void aplicarTravagemPorPersonalidade(double velocidadeAlvo, double distancia, String zona) {
        switch (minhaPersonalidade) {
            case COOPERANTE:
                getOs().changeSpeedWithPleasantAcceleration(velocidadeAlvo);
                System.out.println(String.format(
                        "%-8s [COOPERANTE] %s | travagem SUAVE | dist=%.1f m | alvo=%.2f m/s",
                        getOs().getId(), zona, distancia, velocidadeAlvo
                ));
                break;

            case PADRAO:
                getOs().changeSpeedWithInterval(velocidadeAlvo, 5_000_000_000L);
                System.out.println(String.format(
                        "%-8s [PADRAO] %s | travagem NORMAL | dist=%.1f m | alvo=%.2f m/s",
                        getOs().getId(), zona, distancia, velocidadeAlvo
                ));
                break;

            case POUCO_COOPERANTE:
                getOs().changeSpeedWithInterval(velocidadeAlvo, 2_000_000_000L);
                System.out.println(String.format(
                        "%-8s [POUCO_COOP] %s | travagem BRUSCA | dist=%.1f m | alvo=%.2f m/s",
                        getOs().getId(), zona, distancia, velocidadeAlvo
                ));
                break;

            case NAO_COOPERANTE:
                break;
        }
    }

    private void atribuirPersonalidade() {
        double sorteio = getRandom().nextDouble();

        if (sorteio < 0.20) {
            minhaPersonalidade = Personalidade.COOPERANTE;
        } else if (sorteio < 0.85) {
            minhaPersonalidade = Personalidade.PADRAO;
        } else if (sorteio < 0.95) {
            minhaPersonalidade = Personalidade.POUCO_COOPERANTE;
        } else {
            minhaPersonalidade = Personalidade.NAO_COOPERANTE;
        }
    }

    private void pintarCarro() {
        VehicleParameters.VehicleParametersChangeRequest mudarCor = getOs().requestVehicleParametersUpdate();

        switch (minhaPersonalidade) {
            case COOPERANTE:
                mudarCor.changeColor(Color.GREEN);
                break;
            case PADRAO:
                mudarCor.changeColor(Color.BLUE);
                break;
            case POUCO_COOPERANTE:
                mudarCor.changeColor(Color.MAGENTA);
                break;
            case NAO_COOPERANTE:
                mudarCor.changeColor(Color.RED);
                break;
        }

        getOs().applyVehicleParametersChange(mudarCor);
    }

    @Override
    public void onVehicleUpdated(VehicleData previousVehicleData, VehicleData updatedVehicleData) {
    }

    @Override
    public void onAcknowledgementReceived(ReceivedAcknowledgement acknowledgedMessage) {
    }

    @Override
    public void onCamBuilding(CamBuilder camBuilder) {
    }

    @Override
    public void onMessageTransmitted(V2xMessageTransmission v2xMessageTransmission) {
    }

    @Override
    public void onShutdown() {
        System.out.println(String.format("[STOP] Veículo: %-8s | desligado", getOs().getId()));
    }
}