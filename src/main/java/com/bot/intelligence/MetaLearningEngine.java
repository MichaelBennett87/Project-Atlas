package com.bot.intelligence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

/** Learns how aggressive the next generation's mutations should be. */
public class MetaLearningEngine {
    private final Path reportPath = Path.of(System.getenv().getOrDefault("AI_FULL_EVOLUTION_REPORT_PATH", "logs/ai_full_evolution_report.txt"));
    private final Path outputPath = Path.of(System.getenv().getOrDefault("META_LEARNING_POLICY_PATH", "logs/meta_learning_policy.properties"));
    public MetaLearningResult learn(){double mutationScale=1.0;double exploration=0.15;try{if(Files.exists(reportPath)){String s=Files.readString(reportPath);if(s.contains("promotedPolicy=false")){mutationScale=1.25;exploration=0.25;}if(s.contains("LOW_VOLUME")){mutationScale=0.85;exploration=0.10;}}}catch(Exception ignored){}Properties p=new Properties();p.setProperty("updatedAt", Instant.now().toString());p.setProperty("mutationScale",Double.toString(mutationScale));p.setProperty("explorationRate",Double.toString(exploration));p.setProperty("candidateCountMultiplier",Double.toString(exploration>0.2?1.5:1.0));try{AutonomousEvolutionSuite.FilesUtil.ensureParent(outputPath);try(var out=Files.newOutputStream(outputPath)){p.store(out,"Autonomous meta-learning policy");}}catch(Exception e){throw new RuntimeException(e);}return new MetaLearningResult(mutationScale,exploration,outputPath);}    
    public static final class MetaLearningResult{public final double mutationScale,explorationRate;public final Path path;MetaLearningResult(double m,double e,Path p){mutationScale=m;explorationRate=e;path=p;}public String summary(){return "mutationScale="+mutationScale+" exploration="+explorationRate+" path="+path;}}
}
