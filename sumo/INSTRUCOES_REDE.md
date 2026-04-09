# Como gerar a rede Manhattan com 3 zonas de obras

## Ficheiros necessários (todos na mesma pasta)
- manhattan.nod.xml  — definição dos 25 nós da grelha
- manhattan.edg.xml  — definição das edges (1 lane nas zonas de obras, 2 lanes nas normais)
- manhattan.netccfg  — configuração do netconvert

## Zonas de obras (1 lane, 20 km/h)
- E0  / -E0  → horizontal, J0 ↔ J1  (oeste)
- E2  / -E2  → horizontal, J2 ↔ J3  (centro)
- E32 / -E32 → vertical,   J1 ↔ J21 (coluna x=500)

## Edges normais (2 lanes, 50 km/h)
Todas as outras edges da grelha.

## Comando para gerar a rede
Coloca os 3 ficheiros na pasta sumo/ e corre:

    netconvert -c manhattan.netccfg

Isto gera o manhattan.net.xml com:
- Merge automático (2 lanes → 1 lane) na entrada das zonas de obras
- Bifurcação automática (1 lane → 2 lanes) na saída das zonas de obras
- Todas as conexões entre junctions corretamente calculadas

## Depois de gerar
Testa com:
    sumo-gui -c simulation.sumocfg
