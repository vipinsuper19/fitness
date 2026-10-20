import type { ServingUnit } from '../../src/generated/prisma/client';

export interface SeedFood {
  slug: string;
  name: string;
  nameHi?: string;
  servingSize: number;
  servingUnit: ServingUnit;
  gramsPerServing: number;
  energyKcal: number;
  proteinG: number;
  carbsG: number;
  fatG: number;
  fiberG?: number;
  isVegetarian: boolean;
}

/**
 * Indian-first starter catalog in household servings. Values are typical
 * home-style approximations (per one serving as listed) so users can log
 * quickly; users can edit macros on any entry. Not medical advice.
 */
export const FOODS: SeedFood[] = [
  { slug: 'roti', name: 'Roti / chapati (whole wheat)', nameHi: 'रोटी', servingSize: 1, servingUnit: 'piece', gramsPerServing: 40, energyKcal: 120, proteinG: 3.5, carbsG: 20, fatG: 3, fiberG: 2.5, isVegetarian: true },
  { slug: 'phulka', name: 'Phulka (no ghee)', servingSize: 1, servingUnit: 'piece', gramsPerServing: 30, energyKcal: 85, proteinG: 3, carbsG: 17, fatG: 0.5, fiberG: 2, isVegetarian: true },
  { slug: 'plain-paratha', name: 'Plain paratha', nameHi: 'पराठा', servingSize: 1, servingUnit: 'piece', gramsPerServing: 60, energyKcal: 200, proteinG: 4, carbsG: 26, fatG: 9, fiberG: 3, isVegetarian: true },
  { slug: 'aloo-paratha', name: 'Aloo paratha', servingSize: 1, servingUnit: 'piece', gramsPerServing: 110, energyKcal: 290, proteinG: 6, carbsG: 40, fatG: 12, fiberG: 4, isVegetarian: true },
  { slug: 'steamed-rice', name: 'Steamed white rice', nameHi: 'चावल', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 195, proteinG: 4, carbsG: 43, fatG: 0.5, fiberG: 0.6, isVegetarian: true },
  { slug: 'brown-rice', name: 'Brown rice (cooked)', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 170, proteinG: 4, carbsG: 35, fatG: 1.4, fiberG: 2.7, isVegetarian: true },
  { slug: 'jeera-rice', name: 'Jeera rice', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 240, proteinG: 4, carbsG: 42, fatG: 6, fiberG: 1, isVegetarian: true },
  { slug: 'veg-pulao', name: 'Veg pulao', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 230, proteinG: 5, carbsG: 38, fatG: 7, fiberG: 2.5, isVegetarian: true },
  { slug: 'chicken-biryani', name: 'Chicken biryani', nameHi: 'चिकन बिरयानी', servingSize: 1, servingUnit: 'plate', gramsPerServing: 300, energyKcal: 500, proteinG: 25, carbsG: 60, fatG: 17, fiberG: 2, isVegetarian: false },
  { slug: 'dal-tadka', name: 'Dal tadka (toor)', nameHi: 'दाल तड़का', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 165, proteinG: 8, carbsG: 20, fatG: 6, fiberG: 4, isVegetarian: true },
  { slug: 'moong-dal', name: 'Moong dal', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 140, proteinG: 9, carbsG: 20, fatG: 3, fiberG: 4, isVegetarian: true },
  { slug: 'dal-makhani', name: 'Dal makhani', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 260, proteinG: 9, carbsG: 22, fatG: 15, fiberG: 6, isVegetarian: true },
  { slug: 'rajma', name: 'Rajma curry', nameHi: 'राजमा', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 190, proteinG: 9, carbsG: 26, fatG: 6, fiberG: 7, isVegetarian: true },
  { slug: 'chole', name: 'Chole (chickpea curry)', nameHi: 'छोले', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 210, proteinG: 9, carbsG: 27, fatG: 7, fiberG: 7, isVegetarian: true },
  { slug: 'sambar', name: 'Sambar', nameHi: 'सांभर', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 120, proteinG: 5, carbsG: 16, fatG: 4, fiberG: 4, isVegetarian: true },
  { slug: 'paneer-bhurji', name: 'Paneer bhurji', servingSize: 1, servingUnit: 'katori', gramsPerServing: 120, energyKcal: 290, proteinG: 16, carbsG: 7, fatG: 22, fiberG: 1.5, isVegetarian: true },
  { slug: 'palak-paneer', name: 'Palak paneer', nameHi: 'पालक पनीर', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 260, proteinG: 12, carbsG: 9, fatG: 20, fiberG: 3, isVegetarian: true },
  { slug: 'paneer-raw', name: 'Paneer (raw)', nameHi: 'पनीर', servingSize: 100, servingUnit: 'g', gramsPerServing: 100, energyKcal: 265, proteinG: 18, carbsG: 3.5, fatG: 20, isVegetarian: true },
  { slug: 'mixed-veg-sabzi', name: 'Mixed vegetable sabzi', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 130, proteinG: 3, carbsG: 13, fatG: 7, fiberG: 4, isVegetarian: true },
  { slug: 'aloo-gobi', name: 'Aloo gobi', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 160, proteinG: 3.5, carbsG: 18, fatG: 8, fiberG: 4, isVegetarian: true },
  { slug: 'bhindi-masala', name: 'Bhindi masala', servingSize: 1, servingUnit: 'katori', gramsPerServing: 120, energyKcal: 140, proteinG: 3, carbsG: 11, fatG: 9, fiberG: 4.5, isVegetarian: true },
  { slug: 'chicken-curry', name: 'Chicken curry (home style)', nameHi: 'चिकन करी', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 250, proteinG: 22, carbsG: 6, fatG: 15, fiberG: 1, isVegetarian: false },
  { slug: 'tandoori-chicken', name: 'Tandoori chicken', servingSize: 2, servingUnit: 'piece', gramsPerServing: 150, energyKcal: 260, proteinG: 36, carbsG: 4, fatG: 11, isVegetarian: false },
  { slug: 'fish-curry', name: 'Fish curry', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 210, proteinG: 20, carbsG: 5, fatG: 12, fiberG: 1, isVegetarian: false },
  { slug: 'egg-curry', name: 'Egg curry (2 eggs)', servingSize: 1, servingUnit: 'katori', gramsPerServing: 180, energyKcal: 250, proteinG: 14, carbsG: 8, fatG: 18, fiberG: 1.5, isVegetarian: false },
  { slug: 'boiled-egg', name: 'Boiled egg', nameHi: 'उबला अंडा', servingSize: 1, servingUnit: 'piece', gramsPerServing: 50, energyKcal: 78, proteinG: 6.3, carbsG: 0.6, fatG: 5.3, isVegetarian: false },
  { slug: 'masala-omelette', name: 'Masala omelette (2 eggs)', servingSize: 1, servingUnit: 'piece', gramsPerServing: 120, energyKcal: 210, proteinG: 13, carbsG: 3, fatG: 16, fiberG: 0.5, isVegetarian: false },
  { slug: 'idli', name: 'Idli', nameHi: 'इडली', servingSize: 1, servingUnit: 'piece', gramsPerServing: 40, energyKcal: 58, proteinG: 2, carbsG: 12, fatG: 0.2, fiberG: 0.6, isVegetarian: true },
  { slug: 'plain-dosa', name: 'Plain dosa', nameHi: 'डोसा', servingSize: 1, servingUnit: 'piece', gramsPerServing: 80, energyKcal: 170, proteinG: 4, carbsG: 28, fatG: 4.5, fiberG: 1, isVegetarian: true },
  { slug: 'masala-dosa', name: 'Masala dosa', servingSize: 1, servingUnit: 'piece', gramsPerServing: 160, energyKcal: 330, proteinG: 6.5, carbsG: 48, fatG: 12, fiberG: 3, isVegetarian: true },
  { slug: 'upma', name: 'Upma', nameHi: 'उपमा', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 230, proteinG: 5, carbsG: 34, fatG: 8, fiberG: 2.5, isVegetarian: true },
  { slug: 'poha', name: 'Poha', nameHi: 'पोहा', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 250, proteinG: 5, carbsG: 40, fatG: 8, fiberG: 2, isVegetarian: true },
  { slug: 'besan-chilla', name: 'Besan chilla', servingSize: 1, servingUnit: 'piece', gramsPerServing: 80, energyKcal: 160, proteinG: 7, carbsG: 17, fatG: 7, fiberG: 3, isVegetarian: true },
  { slug: 'oats-milk', name: 'Oats cooked in milk', servingSize: 1, servingUnit: 'katori', gramsPerServing: 200, energyKcal: 250, proteinG: 10, carbsG: 34, fatG: 8, fiberG: 4, isVegetarian: true },
  { slug: 'curd', name: 'Curd / dahi (whole milk)', nameHi: 'दही', servingSize: 1, servingUnit: 'katori', gramsPerServing: 150, energyKcal: 95, proteinG: 5, carbsG: 7, fatG: 5, isVegetarian: true },
  { slug: 'buttermilk', name: 'Chaas / buttermilk', nameHi: 'छाछ', servingSize: 1, servingUnit: 'glass', gramsPerServing: 250, energyKcal: 50, proteinG: 2.5, carbsG: 5, fatG: 2, isVegetarian: true },
  { slug: 'milk-toned', name: 'Toned milk', nameHi: 'दूध', servingSize: 1, servingUnit: 'glass', gramsPerServing: 250, energyKcal: 145, proteinG: 8, carbsG: 12, fatG: 7.5, isVegetarian: true },
  { slug: 'masala-chai', name: 'Masala chai with sugar', nameHi: 'चाय', servingSize: 1, servingUnit: 'cup', gramsPerServing: 150, energyKcal: 90, proteinG: 2.5, carbsG: 13, fatG: 3, isVegetarian: true },
  { slug: 'ghee', name: 'Ghee', nameHi: 'घी', servingSize: 1, servingUnit: 'tsp', gramsPerServing: 5, energyKcal: 45, proteinG: 0, carbsG: 0, fatG: 5, isVegetarian: true },
  { slug: 'banana', name: 'Banana (medium)', nameHi: 'केला', servingSize: 1, servingUnit: 'piece', gramsPerServing: 118, energyKcal: 105, proteinG: 1.3, carbsG: 27, fatG: 0.4, fiberG: 3.1, isVegetarian: true },
  { slug: 'apple', name: 'Apple (medium)', nameHi: 'सेब', servingSize: 1, servingUnit: 'piece', gramsPerServing: 180, energyKcal: 95, proteinG: 0.5, carbsG: 25, fatG: 0.3, fiberG: 4.4, isVegetarian: true },
  { slug: 'roasted-chana', name: 'Roasted chana', servingSize: 30, servingUnit: 'g', gramsPerServing: 30, energyKcal: 110, proteinG: 6, carbsG: 17, fatG: 1.8, fiberG: 5, isVegetarian: true },
  { slug: 'samosa', name: 'Samosa', nameHi: 'समोसा', servingSize: 1, servingUnit: 'piece', gramsPerServing: 70, energyKcal: 260, proteinG: 4, carbsG: 26, fatG: 16, fiberG: 2, isVegetarian: true },
  { slug: 'whey-protein', name: 'Whey protein (1 scoop, in water)', servingSize: 1, servingUnit: 'piece', gramsPerServing: 30, energyKcal: 120, proteinG: 24, carbsG: 3, fatG: 1.5, isVegetarian: true },
];
