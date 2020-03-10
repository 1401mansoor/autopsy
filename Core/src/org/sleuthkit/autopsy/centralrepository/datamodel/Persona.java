/*
 * Central Repository
 *
 * Copyright 2020 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.centralrepository.datamodel;

/**
 * This class abstracts a persona.
 *
 * An examiner may create a persona from an account.
 *
 */
class Persona {

    /**
     * Defines level of confidence in assigning a persona to an account.
     */
    public enum Confidence {
        UNKNOWN(1, "Unknown"),
        LOW(2, "Low confidence"),
        MEDIUM(3, "Medium confidence"),
        HIGH(4, "High confidence"),
        DERIVED(5, "Derived directly");

        private final String name;
        private final int level_id;

        Confidence(int level, String name) {
            this.name = name;
            this.level_id = level;

        }

        @Override
        public String toString() {
            return name;
        }

        public int getLevel() {
            return this.level_id;
        }
    }

    /**
     * Defines status of a persona.
     */
    public enum PersonaStatus {

        UNKNOWN(1, "Unknown"),
        ACTIVE(2, "Active"),
        MERGED(3, "Merged"),
        SPLIT(4, "Split"),
        DELETED(5, "Deleted");

        private final String description;
        private final int status_id;

        PersonaStatus(int status, String description) {
            this.status_id = status;
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }

        public int getStatus() {
            return this.status_id;
        }
    }

}
