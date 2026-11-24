export type SkillOption = { value: string; label: string; title: string };
export type ConnectedView = { options: SkillOption[]; openChat: (id: string) => void };
