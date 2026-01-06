import type { NavigationInstruction, InstructionType } from './types';

export class VoiceGuidance {
  private synth: SpeechSynthesis;
  private enabled: boolean = true;
  private lastSpokenInstruction: number = -1;

  constructor() {
    this.synth = window.speechSynthesis;
  }

  setEnabled(enabled: boolean) {
    this.enabled = enabled;
    if (!enabled) {
      this.synth.cancel();
    }
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  speak(text: string, interrupt: boolean = false) {
    if (!this.enabled) return;

    if (interrupt) {
      this.synth.cancel();
    }

    const utterance = new SpeechSynthesisUtterance(text);
    utterance.rate = 1.0;
    utterance.pitch = 1.0;
    utterance.volume = 1.0;
    utterance.lang = 'en-US';

    this.synth.speak(utterance);
  }

  announceInstruction(instruction: NavigationInstruction, distance: number, index: number) {
    // Don't repeat the same instruction
    if (index === this.lastSpokenInstruction) return;

    const text = this.buildInstructionText(instruction, distance);
    this.speak(text, true);
    this.lastSpokenInstruction = index;
  }

  announceApproaching(instruction: NavigationInstruction, distance: number) {
    const directionText = this.getDirectionText(instruction.type);
    const distanceText = this.formatDistance(distance);
    const streetText = instruction.streetName ? ` onto ${instruction.streetName}` : '';

    this.speak(`In ${distanceText}, ${directionText}${streetText}`);
  }

  announceOffRoute() {
    this.speak('You are off route. Please return to the route.', true);
  }

  announceStart(streetName: string | null) {
    const text = streetName
      ? `Starting navigation. Head along ${streetName}`
      : 'Starting navigation. Follow the route';
    this.speak(text, true);
  }

  announceArrival() {
    this.speak('You have arrived. Survey complete. All streets have been covered.', true);
  }

  private buildInstructionText(instruction: NavigationInstruction, distance: number): string {
    const direction = this.getDirectionText(instruction.type);
    const street = instruction.streetName ? ` onto ${instruction.streetName}` : '';
    const dist = this.formatDistance(distance);

    switch (instruction.type) {
      case 'start':
        return instruction.streetName
          ? `Start on ${instruction.streetName}`
          : 'Start navigation';
      case 'arrived':
        return 'You have arrived at your destination';
      default:
        return `${direction}${street}. Continue for ${dist}`;
    }
  }

  private getDirectionText(type: InstructionType): string {
    switch (type) {
      case 'start': return 'Start';
      case 'continue': return 'Continue straight';
      case 'turn_left': return 'Turn left';
      case 'turn_right': return 'Turn right';
      case 'slight_left': return 'Slight left';
      case 'slight_right': return 'Slight right';
      case 'sharp_left': return 'Sharp left';
      case 'sharp_right': return 'Sharp right';
      case 'u_turn': return 'Make a U-turn';
      case 'arrived': return 'Arrived';
    }
  }

  private formatDistance(meters: number): string {
    if (meters < 100) {
      return `${Math.round(meters / 10) * 10} meters`;
    } else if (meters < 1000) {
      return `${Math.round(meters / 50) * 50} meters`;
    } else {
      const km = meters / 1000;
      return km < 10
        ? `${km.toFixed(1)} kilometers`
        : `${Math.round(km)} kilometers`;
    }
  }

  reset() {
    this.lastSpokenInstruction = -1;
    this.synth.cancel();
  }
}

export function getInstructionIcon(type: InstructionType): string {
  switch (type) {
    case 'start': return '▶';
    case 'continue': return '↑';
    case 'turn_left': return '←';
    case 'turn_right': return '→';
    case 'slight_left': return '↖';
    case 'slight_right': return '↗';
    case 'sharp_left': return '↰';
    case 'sharp_right': return '↱';
    case 'u_turn': return '↩';
    case 'arrived': return '🏁';
  }
}

export function getInstructionText(type: InstructionType): string {
  switch (type) {
    case 'start': return 'Start';
    case 'continue': return 'Continue';
    case 'turn_left': return 'Turn left';
    case 'turn_right': return 'Turn right';
    case 'slight_left': return 'Slight left';
    case 'slight_right': return 'Slight right';
    case 'sharp_left': return 'Sharp left';
    case 'sharp_right': return 'Sharp right';
    case 'u_turn': return 'U-turn';
    case 'arrived': return 'Arrived';
  }
}

export function formatDistance(meters: number): string {
  if (meters < 1000) {
    return `${Math.round(meters)}m`;
  } else {
    return `${(meters / 1000).toFixed(1)}km`;
  }
}

export function formatTime(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes} min`;
}
