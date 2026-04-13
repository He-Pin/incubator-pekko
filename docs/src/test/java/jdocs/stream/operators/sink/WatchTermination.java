/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package jdocs.stream.operators.sink;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.apache.pekko.Done;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;

public class WatchTermination {

  static final ActorSystem system = ActorSystem.create("WatchTerminationExample");

  void watchTerminationExample() {
    // #watchTermination
    // Wrap Sink.ignore with termination watching
    Sink<Integer, CompletionStage<Done>> terminationWatchedSink =
        Sink.watchTermination(
            Sink.ignore(),
            (mat, completionStage) -> {
              completionStage.whenComplete(
                  (done, exc) -> {
                    if (exc != null) {
                      System.out.println("Stream failed: " + exc.getMessage());
                    } else {
                      System.out.println("Stream completed successfully");
                    }
                  });
              return completionStage;
            });

    Source.from(Arrays.asList(1, 2, 3, 4, 5)).runWith(terminationWatchedSink, system);
    /*
    Prints:
    Stream completed successfully
     */

    // Combine the original materialized value with the termination signal
    Sink<Integer, Pair<CompletionStage<List<Integer>>, CompletionStage<Done>>> sinkWithBoth =
        Sink.watchTermination(Sink.seq(), Pair::create);

    Pair<CompletionStage<List<Integer>>, CompletionStage<Done>> result =
        Source.from(Arrays.asList(1, 2, 3)).runWith(sinkWithBoth, system);

    result.first().thenAccept(seq -> System.out.println("Collected: " + seq));

    result
        .second()
        .whenComplete(
            (done, exc) -> {
              if (exc != null) {
                System.out.println("Stream terminated with error: " + exc.getMessage());
              } else {
                System.out.println("Stream terminated normally");
              }
            });
    /*
    Prints:
    Collected: [1, 2, 3]
    Stream terminated normally
     */
    // #watchTermination
  }
}
