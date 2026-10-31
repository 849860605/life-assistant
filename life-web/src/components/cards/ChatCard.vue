<template>
  <RestaurantCard v-if="card.kind === 'restaurants'" :data="card.data" />
  <FlightCard v-else-if="card.kind === 'flights'" :data="card.data" />
  <HotelCard v-else-if="card.kind === 'hotels'" :data="card.data" />
  <TripCard v-else-if="card.kind === 'trip'" :data="card.data" />
  <PlanOptionsCard v-else-if="card.kind === 'plan_options'" :data="card.data" :active="active" :conversation-id="conversationId" @submit="emit('submit', $event)" />
  <RequirementCard v-else-if="card.kind === 'requirement'" :data="card.data" :active="active" @submit="emit('submit', $event)" />
</template>

<script setup lang="ts">
import type { Card } from "../../api/types";
import FlightCard from "./FlightCard.vue";
import HotelCard from "./HotelCard.vue";
import PlanOptionsCard from "./PlanOptionsCard.vue";
import RequirementCard from "./RequirementCard.vue";
import RestaurantCard from "./RestaurantCard.vue";
import TripCard from "./TripCard.vue";

/** 按卡片类型选组件；不认识的类型不显示。active 给需求卡和方案卡用：只有最新一条回答里的卡片能提交。 */
defineProps<{ card: Card; active: boolean; conversationId?: string | null }>();
const emit = defineEmits<{ (e: "submit", text: string): void }>();
</script>
